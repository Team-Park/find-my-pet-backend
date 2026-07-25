package com.park.animal.searchgroup

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.GroupAccess
import com.park.animal.searchgroup.access.GroupRole
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.dto.SearchGroupCtaResponse
import com.park.animal.searchgroup.dto.SearchGroupDetailResponse
import com.park.animal.searchgroup.dto.SearchGroupEventResponse
import com.park.animal.searchgroup.dto.SearchGroupViewerAction
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroup
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchgroup.repository.SearchGroupEventRepository
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 수색그룹 조회·종료 진입점 (계약 §8 #2·#3·#5·#6).
 *
 * 권한 판정은 전부 [SearchGroupAccessResolver] 에 위임한다. 이 클래스는 판정 결과를 DTO 로
 * 옮기는 일만 한다 — 여기서 `role` 이나 `blocked` 를 다시 해석하면 판정이 두 곳으로 갈라진다.
 *
 * 참여 정책 변경(`PATCH /search-groups/{groupId}/join-policy`, 계약 §8 #4)은 **Task 6 이
 * 이 클래스에 `updateJoinPolicy` 로 추가**한다. 여기서는 만들지 않는다.
 */
@Service
class SearchGroupService(
    private val accessResolver: SearchGroupAccessResolver,
    private val searchGroupRepository: SearchGroupRepository,
    private val searchGroupMemberRepository: SearchGroupMemberRepository,
    private val searchGroupTeamRepository: SearchGroupTeamRepository,
    private val searchGroupEventRepository: SearchGroupEventRepository,
    private val postRepository: PostRepository,
    private val searchLifecycleService: SearchLifecycleService,
) {
    companion object {
        /** 활동 기록 한 페이지 상한. 설계 §16.7 "서버가 결과 수를 제한한다". */
        const val MAX_EVENT_PAGE_SIZE = 50
    }

    /**
     * 공개 CTA (계약 §8 #2, 설계 §11). 비로그인 [viewerId] = null 을 허용한다.
     *
     * 그룹이 없는 글(`SEEN` 으로 등록돼 백필 대상이 아니었던 글)과 soft-delete 된 글은
     * 똑같이 404 다 — 어느 쪽인지 구분할 수 있으면 삭제 사실이 새어나간다.
     *
     * [SearchGroupViewerAction] 결정 순서가 곧 개인정보 계약이다. 차단 판정을 가장 먼저 두고,
     * 종료·목격 상태를 그다음에 둔다. 그 결과 차단자는 종료된 수색·목격 소식과 완전히 같은
     * `UNAVAILABLE` 을 받는다(설계 §6.3, 계약 §9).
     */
    @Transactional(readOnly = true)
    fun getCta(
        postId: UUID,
        viewerId: UUID?,
    ): SearchGroupCtaResponse {
        val access =
            accessResolver.resolveByPostId(postId, viewerId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
        if (!access.visible) throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)

        val action =
            when {
                access.blocked -> SearchGroupViewerAction.UNAVAILABLE
                access.groupStatus != SearchGroupStatus.ACTIVE -> SearchGroupViewerAction.UNAVAILABLE
                access.postStatus != MissingAnimalStatus.SEARCHING -> SearchGroupViewerAction.UNAVAILABLE
                access.role != GroupRole.NONE -> SearchGroupViewerAction.ALREADY_JOINED
                viewerId == null -> SearchGroupViewerAction.LOGIN_REQUIRED
                access.joinPolicy == JoinPolicy.OPEN -> SearchGroupViewerAction.JOIN_NOW
                else -> SearchGroupViewerAction.REQUEST_JOIN
            }

        // UNAVAILABLE 은 차단/종료/목격 세 원인을 하나로 뭉뚱그린 값이다. status/postStatus 를
        // 그대로 내보내면 그 뭉뚱그림이 무의미해진다 — ACTIVE+SEARCHING 조합은 차단된 뷰어만
        // 도달할 수 있는 유일한 조합이므로, 값을 그대로 노출하면 그것만으로 차단 여부가 드러난다.
        val unavailable = action == SearchGroupViewerAction.UNAVAILABLE

        return SearchGroupCtaResponse(
            postId = access.postId,
            groupId = access.groupId,
            joinPolicy = access.joinPolicy,
            status = if (unavailable) null else access.groupStatus,
            postStatus = if (unavailable) null else access.postStatus,
            memberCount = activeMemberCount(access.groupId),
            teamCount = activeTeamCount(access.groupId),
            viewerAction = action,
        )
    }

    /** 그룹 상세 (계약 §8 #3). 유효 참여자만 볼 수 있다 — 차단·비참여는 같은 403. */
    @Transactional(readOnly = true)
    fun getDetail(
        groupId: UUID,
        viewerId: UUID,
    ): SearchGroupDetailResponse {
        val access = accessResolver.requireRead(groupId, viewerId)
        val group =
            searchGroupRepository.findByIdAndDeletedAtIsNull(groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
        val post =
            postRepository.findByIdAndDeletedAtIsNull(group.postId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
        return toDetail(access, group, post)
    }

    /**
     * 보호자의 수색 종료 (계약 §8 #5, 설계 §3.12/§14.1/§22.7).
     *
     * [SearchGroupAccessResolver.requireOwner] 가 (a) 보호자가 아니면 403,
     * (b) 이미 ARCHIVED 면 410 을 내므로 두 번째 호출은 여기서 걸린다 —
     * 감사 기록과 알림이 두 번 생기지 않는다. 경합으로 두 요청이 동시에 통과해도
     * `archiveIfStatus` 의 조건부 UPDATE 가 한쪽만 1행을 바꾸므로 fan-out 은 한 번뿐이다.
     *
     * 응답은 판정 시점의 [GroupAccess] 와 **전이 후** 엔티티를 섞어 만든다.
     * 접근 판정은 native SQL 이라 방금 flush 되지 않은 변경을 보지 못하므로 재판정하지 않는다.
     */
    @Transactional
    fun endSearch(
        groupId: UUID,
        actorUserId: UUID,
    ): SearchGroupDetailResponse {
        val access = accessResolver.requireOwner(groupId, actorUserId)
        val group = searchLifecycleService.endSearch(access.groupId, actorUserId)
        val post =
            postRepository.findByIdAndDeletedAtIsNull(group.postId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_POST)
        return toDetail(access, group, post)
    }

    /**
     * 그룹 활동 기록 (계약 §8 #6, 설계 §10 활동 탭). 유효 참여자만 조회한다.
     *
     * 정렬은 `createdAt DESC, id DESC` 다 — `DATETIME(6)` 이라도 같은 마이크로초 다건이
     * 페이지 경계에 걸리면 offset 페이지네이션에서 중복/누락이 나므로 tiebreaker 를 둔다(F20).
     */
    @Transactional(readOnly = true)
    fun listEvents(
        groupId: UUID,
        viewerId: UUID,
        size: Int,
        offset: Int,
    ): List<SearchGroupEventResponse> {
        accessResolver.requireRead(groupId, viewerId)
        val pageSize = size.coerceIn(1, MAX_EVENT_PAGE_SIZE)
        val pageNumber = (offset.coerceAtLeast(0)) / pageSize
        return searchGroupEventRepository
            .findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId, PageRequest.of(pageNumber, pageSize))
            .content
            .map(SearchGroupEventResponse::from)
    }

    private fun toDetail(
        access: GroupAccess,
        group: SearchGroup,
        post: Post,
    ): SearchGroupDetailResponse {
        val isOwner = access.isOwner
        val active = group.status == SearchGroupStatus.ACTIVE
        return SearchGroupDetailResponse(
            groupId = group.id,
            postId = group.postId,
            postTitle = post.title,
            joinPolicy = group.joinPolicy,
            status = group.status,
            postStatus = post.missingAnimalStatus,
            archivedReason = group.archivedReason,
            archivedAt = group.archivedAt,
            role = access.role,
            sources = access.sources,
            memberCount = activeMemberCount(group.id),
            teamCount = activeTeamCount(group.id),
            pendingMemberCount =
                if (isOwner) {
                    searchGroupMemberRepository.countByGroupIdAndStatus(group.id, SearchGroupMemberStatus.PENDING)
                } else {
                    null
                },
            pendingTeamCount =
                if (isOwner) {
                    searchGroupTeamRepository.countByGroupIdAndStatus(
                        group.id,
                        SearchGroupTeamStatus.PENDING_GROUP_APPROVAL,
                    )
                } else {
                    null
                },
            myMembershipId = access.directMembershipId,
            myMembershipStatus = access.directMembershipStatus,
            canManage = isOwner && active,
            canWrite = access.role != GroupRole.NONE && active,
        )
    }

    private fun activeMemberCount(groupId: UUID): Long =
        searchGroupMemberRepository.countByGroupIdAndStatus(groupId, SearchGroupMemberStatus.ACTIVE)

    private fun activeTeamCount(groupId: UUID): Long =
        searchGroupTeamRepository.countByGroupIdAndStatus(groupId, SearchGroupTeamStatus.ACTIVE)
}
