package com.park.animal.searchgroup

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.entity.NotificationType
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.entity.ArchivedReason
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroup
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.repository.SearchGroupRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 수색 생명주기 단일 진입점 (설계 §14.1).
 *
 * 실종 소식의 상태를 바꿀 수 있는 모든 경로 — `POST /post`, `PUT /post`,
 * `PATCH /post/renewal-status`, `DELETE /post/{id}`, `POST /search-groups/{id}/end` — 는
 * 반드시 이 서비스를 통해야 한다. 어떤 API 를 써도 `FOUND` 전환이 그룹 보관을 우회할 수 없다.
 *
 * 전이 규칙(계약 §9):
 * | 대상 상태 | 그룹 없음 | ACTIVE 그룹 | ARCHIVED 그룹 |
 * |---|---|---|---|
 * | SEARCHING | OPEN 그룹 생성(legacy 글 구제) | 유지 | 410 SEARCH_ALREADY_ENDED |
 * | SEEN | 생성하지 않음 | 유지 (archivedReason 에 SEEN 이 없다) | (도달 불가) |
 * | FOUND | post 만 전이 | endSearch | endSearch → 멱등 no-op |
 *
 * **detach 주의**: [SearchGroupRepository.archiveIfStatus] 는 `clearAutomatically = true` 다.
 * 이 서비스 호출 이후 호출부가 이전에 들고 있던 `Post` 엔티티는 detached 이므로
 * 추가 변경을 하려면 다시 읽어야 한다. 이미 로드된 스칼라 필드 읽기는 안전하다.
 *
 * **알림**: [endSearch] 의 fan-out 은 [GroupNotificationPublisher.notifyGroup] 하나로 처리한다.
 * 수신자 계산(effectiveMemberIds native SQL)과 문구(`GroupNotificationTemplates`)를 모두
 * publisher 에 위임하므로, 이 파일에는 알림 문구 문자열이 없다.
 */
@Service
class SearchLifecycleService(
    private val searchGroupRepository: SearchGroupRepository,
    private val postRepository: PostRepository,
    private val searchGroupEventRecorder: SearchGroupEventRecorder,
    private val notificationPublisher: GroupNotificationPublisher,
) {
    /**
     * 실종 소식에 수색그룹을 연다. `SEARCHING` 이 아니면 그룹을 만들지 않고 null 을 돌려준다(설계 §6.1).
     *
     * 자연키(`post_id` UNIQUE) 선조회 + 없을 때만 삽입으로 멱등성을 얻는다.
     * duplicate-key 를 catch 해서 재조회하면 트랜잭션이 rollback-only 로 오염돼 커밋 시 500 이 된다(F14).
     */
    @Transactional
    fun openGroupForPost(
        post: Post,
        joinPolicy: JoinPolicy,
    ): SearchGroup? {
        if (post.missingAnimalStatus != MissingAnimalStatus.SEARCHING) return null
        searchGroupRepository.findByPostIdAndDeletedAtIsNull(post.id)?.let { return it }

        val saved = searchGroupRepository.save(SearchGroup(postId = post.id, joinPolicy = joinPolicy))
        searchGroupEventRecorder.record(
            groupId = saved.id,
            type = SearchGroupEventType.GROUP_OPENED,
            actorId = post.authorId,
            targetId = null,
            detail = "joinPolicy=${joinPolicy.name}",
        )
        return saved
    }

    /**
     * 실종 소식 상태 전이. 상태 필드를 직접 만지는 유일한 지점이다.
     * 호출부는 `post.missingAnimalStatus` 를 스스로 대입하지 않는다.
     */
    @Transactional
    fun applyStatusTransition(
        post: Post,
        actorUserId: UUID,
        next: MissingAnimalStatus,
    ) {
        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(post.id)
        when (next) {
            MissingAnimalStatus.FOUND -> {
                if (group == null) {
                    // 기능 도입 전에 만들어진 글이라 그룹이 없다. 상태만 전이한다.
                    post.updateStatus(MissingAnimalStatus.FOUND)
                    return
                }
                endSearch(group.id, actorUserId)
            }

            MissingAnimalStatus.SEARCHING -> {
                if (group == null) {
                    post.updateStatus(MissingAnimalStatus.SEARCHING)
                    openGroupForPost(post, JoinPolicy.OPEN)
                    return
                }
                if (group.status == SearchGroupStatus.ARCHIVED) {
                    // 설계 §7 — 종료된 수색그룹의 재활성화 API 는 없다. 새 실종 소식을 등록해야 한다.
                    throw BusinessException(ErrorCode.SEARCH_ALREADY_ENDED)
                }
                post.updateStatus(MissingAnimalStatus.SEARCHING)
            }

            MissingAnimalStatus.SEEN -> {
                if (group != null && group.status == SearchGroupStatus.ARCHIVED) {
                    // 전이 표의 ARCHIVED×SEEN "(도달 불가)" 를 실제로 막는다. 여기서 조용히
                    // 통과시키면 post 가 ARCHIVED 그룹 아래에서 SEEN 이 되고, 그 다음 재-FOUND
                    // 호출이 endSearch 의 조건부 UPDATE(이미 ARCHIVED → 0행)에 막혀 post.updateStatus
                    // 를 건너뛴 채 200 을 반환한다 — post 는 SEEN 인 채로 영원히 남는다(coordinator
                    // review, Task 4 fix round). SEARCHING 분기와 동일하게 410 으로 막아 그 상태
                    // 자체를 도달 불가능하게 만든다.
                    throw BusinessException(ErrorCode.SEARCH_ALREADY_ENDED)
                }
                post.updateStatus(MissingAnimalStatus.SEEN)
            }
        }
    }

    /**
     * 보호자의 수색 종료 (설계 §14.1). 한 트랜잭션에서 post→FOUND, group→ARCHIVED(FOUND),
     * 감사 기록, 유효 참여자 알림 fan-out 을 처리한다.
     *
     * 권한 검사는 하지 않는다 — HTTP 진입점인 `SearchGroupService.endSearch` 가
     * `requireOwner` 로 이미 판정했고, `PostService` 경로는 작성자 검사를 먼저 한다.
     *
     * 재시도 안전(설계 §15): 조건부 UPDATE 가 0행이면 이미 종료된 것이므로 410 이 아니라
     * 현재 상태를 그대로 돌려준다. 감사·알림이 두 번 생기지 않는다.
     */
    @Transactional
    fun endSearch(
        groupId: UUID,
        actorUserId: UUID,
    ): SearchGroup {
        val group =
            searchGroupRepository.findByIdAndDeletedAtIsNull(groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
        val postId = group.postId

        val archivedAt = LocalDateTime.now()
        val affected =
            searchGroupRepository.archiveIfStatus(
                groupId = groupId,
                expected = SearchGroupStatus.ACTIVE,
                next = SearchGroupStatus.ARCHIVED,
                reason = ArchivedReason.FOUND,
                archivedAt = archivedAt,
                archivedBy = actorUserId,
            )
        if (affected == 0) {
            return searchGroupRepository.findByIdAndDeletedAtIsNull(groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
        }

        // archiveIfStatus 가 1차 캐시를 비웠으므로 다시 읽어 관리 상태로 만든다.
        val post =
            postRepository.findByIdAndDeletedAtIsNull(postId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_POST)
        post.updateStatus(MissingAnimalStatus.FOUND)

        searchGroupEventRecorder.record(
            groupId = groupId,
            type = SearchGroupEventType.SEARCH_ENDED,
            actorId = actorUserId,
            targetId = null,
            detail = "status=ARCHIVED,reason=FOUND",
        )
        // 제목·본문·링크는 GroupNotificationTemplates 표 하나만 쓴다(하드코딩 문구 금지).
        // 이 호출이 group_id / post_id / actor_user_id 를 채운다 — Task 11 의 알림 검증이 여기에 의존한다.
        notificationPublisher.notifyGroup(
            groupId = groupId,
            postId = postId,
            type = NotificationType.SEARCH_ENDED,
            excluding = setOf(actorUserId),
            actorUserId = actorUserId,
            body = null,
        )

        return searchGroupRepository.findByIdAndDeletedAtIsNull(groupId)
            ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
    }

    /**
     * 실종 소식 삭제에 따른 그룹 보관 (설계 §14.2).
     *
     * **반드시 `PostService.deletePost` 가 post 를 하드 삭제하기 전에 호출한다.** `Post` 의
     * `@SQLDelete` 는 커스텀 UPDATE 라 in-memory `post.deletedAt` 을 채우지 않고(F4),
     * `deletedAt IS NULL` 조회에도 즉시 반영되지 않는다. 순서를 뒤집으면 삭제된 글의 그룹이
     * ACTIVE 로 남는다.
     *
     * 알림은 보내지 않는다 — 설계 §9 수신자 표에 실종 소식 삭제 항목이 없다.
     */
    @Transactional
    fun archiveOnPostDeleted(
        post: Post,
        actorUserId: UUID,
    ) {
        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(post.id) ?: return
        val affected =
            searchGroupRepository.archiveIfStatus(
                groupId = group.id,
                expected = SearchGroupStatus.ACTIVE,
                next = SearchGroupStatus.ARCHIVED,
                reason = ArchivedReason.POST_DELETED,
                archivedAt = LocalDateTime.now(),
                archivedBy = actorUserId,
            )
        if (affected == 0) return

        searchGroupEventRecorder.record(
            groupId = group.id,
            type = SearchGroupEventType.GROUP_ARCHIVED_BY_POST_DELETE,
            actorId = actorUserId,
            targetId = null,
            detail = "status=ARCHIVED,reason=POST_DELETED",
        )
    }
}
