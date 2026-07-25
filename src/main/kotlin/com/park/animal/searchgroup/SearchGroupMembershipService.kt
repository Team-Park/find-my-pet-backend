package com.park.animal.searchgroup

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.entity.NotificationType
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.access.GroupAccess
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.dto.JoinSearchGroupResponse
import com.park.animal.searchgroup.dto.SearchGroupMembershipResponse
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupMember
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 개인의 직접 참여 lifecycle (설계 §8, §14.3).
 *
 * 네 가지 규칙이 이 클래스 전체를 지배한다.
 * 1. 재가입은 **새 INSERT 가 아니라 기존 행의 status 전이**다. `(group_id, user_id)` UNIQUE 와
 *    soft-delete 조합이 재가입을 영구히 막는 사고(post_bookmark)를 여기서는 반복하지 않는다.
 * 2. 모든 전이는 `WHERE id = :membershipId AND group_id = :groupId AND status = :expected` 조건부
 *    UPDATE 다. 영향 행 0 은 "이미 다른 상태" 로만 해석하고, 목표 상태면 멱등 성공으로 처리한다.
 *    비관적 락은 쓰지 않는다 — 이 레포에 선례가 없고(F23) 조건부 전이가 경합을 이미 판정한다.
 * 3. duplicate-key 를 catch 해서 재조회하지 않는다. `@Transactional` 안에서 duplicate 가 나면
 *    트랜잭션이 rollback-only 로 오염돼 커밋 시 500 이 되기 때문이다(F14). 대신 자연키를
 *    **먼저 조회**해 duplicate 상황 자체를 만들지 않는다.
 * 4. ACTIVE 로 올리는 전이는 activate()(joinedAt 갱신), 그 밖의 전이는 transition() 을 쓴다.
 */
@Service
class SearchGroupMembershipService(
    private val memberRepository: SearchGroupMemberRepository,
    private val searchGroupRepository: SearchGroupRepository,
    private val accessResolver: SearchGroupAccessResolver,
    private val eventRecorder: SearchGroupEventRecorder,
    private val notificationPublisher: GroupNotificationPublisher,
    private val meterRegistry: MeterRegistry,
) {
    /**
     * `함께 찾기` / `참여 신청`.
     *
     * - OPEN → ACTIVE, APPROVAL_REQUIRED → PENDING
     * - 이미 ACTIVE 면 같은 결과를 그대로 반환한다(멱등). 409 가 아니다.
     * - APPROVAL_REQUIRED 에서 PENDING 상태로 다시 누르면 PENDING 을 유지한다(멱등).
     * - APPROVAL_REQUIRED → OPEN 으로 정책이 바뀐 뒤 PENDING 사용자가 다시 누르면 ACTIVE 로 전이한다(설계 §8.3).
     */
    @Transactional
    fun join(
        groupId: UUID,
        userId: UUID,
        userName: String?,
    ): JoinSearchGroupResponse {
        val access = accessResolver.requireVisible(groupId, userId)
        if (access.isOwner) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)

        val existing = memberRepository.findByGroupIdAndUserId(groupId, userId)
        if (existing != null && existing.status == SearchGroupMemberStatus.ACTIVE) {
            return JoinSearchGroupResponse.of(existing, access.joinPolicy)
        }
        if (existing != null &&
            existing.status == SearchGroupMemberStatus.PENDING &&
            access.joinPolicy == JoinPolicy.APPROVAL_REQUIRED
        ) {
            return JoinSearchGroupResponse.of(existing, access.joinPolicy)
        }
        if (!access.canJoin) throw joinRejection(access)

        val now = LocalDateTime.now()
        val target =
            if (access.joinPolicy == JoinPolicy.OPEN) {
                SearchGroupMemberStatus.ACTIVE
            } else {
                SearchGroupMemberStatus.PENDING
            }

        val membershipId: UUID =
            if (existing == null) {
                memberRepository
                    .save(
                        SearchGroupMember(
                            groupId = groupId,
                            userId = userId,
                            userName = userName,
                            status = target,
                            requestedAt = now,
                            joinedAt = if (target == SearchGroupMemberStatus.ACTIVE) now else null,
                        ),
                    ).id
            } else {
                val affected =
                    if (target == SearchGroupMemberStatus.ACTIVE) {
                        memberRepository.activate(
                            membershipId = existing.id,
                            groupId = groupId,
                            expected = existing.status,
                            decidedBy = userId,
                            occurredAt = now,
                        )
                    } else {
                        // 재신청은 "결정" 이 아니므로 decidedBy 를 null 로 넘겨 이전 결정자를 지운다.
                        memberRepository.transition(
                            membershipId = existing.id,
                            groupId = groupId,
                            expected = existing.status,
                            next = SearchGroupMemberStatus.PENDING,
                            decidedBy = null,
                            occurredAt = now,
                        )
                    }
                if (affected == 0) {
                    val current =
                        memberRepository.findByIdAndGroupId(existing.id, groupId)
                            ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP)
                    if (current.status != target) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
                }
                existing.id
            }

        val saved =
            memberRepository.findByIdAndGroupId(membershipId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP)

        if (target == SearchGroupMemberStatus.ACTIVE) {
            eventRecorder.record(groupId, SearchGroupEventType.MEMBER_JOINED, userId, userId, null)
            notificationPublisher.notifyOwner(access, NotificationType.GROUP_MEMBER_JOINED, userId, userName, null)
        } else {
            eventRecorder.record(groupId, SearchGroupEventType.MEMBER_REQUESTED, userId, userId, null)
            notificationPublisher.notifyOwner(access, NotificationType.GROUP_JOIN_REQUESTED, userId, userName, null)
            // 설계 §20 관측: 승인 대기 생성 수. id·이름은 label 에 넣지 않는다.
            meterRegistry.counter(JOIN_REQUESTED_METRIC).increment()
        }
        return JoinSearchGroupResponse.of(saved, access.joinPolicy)
    }

    /** 보호자의 참여 요청 승인. PENDING 이 아니면 409, 이미 ACTIVE 면 멱등 성공. */
    @Transactional
    fun approve(
        groupId: UUID,
        membershipId: UUID,
        ownerUserId: UUID,
    ): SearchGroupMembershipResponse =
        decide(
            groupId = groupId,
            membershipId = membershipId,
            ownerUserId = ownerUserId,
            target = SearchGroupMemberStatus.ACTIVE,
            eventType = SearchGroupEventType.MEMBER_APPROVED,
            notificationType = NotificationType.GROUP_JOIN_APPROVED,
        )

    /** 보호자의 참여 요청 거절. */
    @Transactional
    fun reject(
        groupId: UUID,
        membershipId: UUID,
        ownerUserId: UUID,
    ): SearchGroupMembershipResponse =
        decide(
            groupId = groupId,
            membershipId = membershipId,
            ownerUserId = ownerUserId,
            target = SearchGroupMemberStatus.REJECTED,
            eventType = SearchGroupEventType.MEMBER_REJECTED,
            notificationType = NotificationType.GROUP_JOIN_REJECTED,
        )

    /** 보호자의 참여자 내보내기. ACTIVE 만 REMOVED 로 보낸다. */
    @Transactional
    fun remove(
        groupId: UUID,
        membershipId: UUID,
        ownerUserId: UUID,
    ): SearchGroupMembershipResponse {
        val access = accessResolver.requireOwner(groupId, ownerUserId)
        val member =
            memberRepository.findByIdAndGroupId(membershipId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP)
        if (member.status == SearchGroupMemberStatus.REMOVED) return SearchGroupMembershipResponse.from(member)
        if (member.status != SearchGroupMemberStatus.ACTIVE) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)

        val now = LocalDateTime.now()
        val affected =
            memberRepository.transition(
                membershipId = member.id,
                groupId = groupId,
                expected = SearchGroupMemberStatus.ACTIVE,
                next = SearchGroupMemberStatus.REMOVED,
                decidedBy = ownerUserId,
                occurredAt = now,
            )
        val current = reloadOrConflict(groupId, member.id, SearchGroupMemberStatus.REMOVED, affected)

        eventRecorder.record(groupId, SearchGroupEventType.MEMBER_REMOVED, ownerUserId, member.userId, null)
        // 행위자·사유를 대상자에게 알리지 않는다 (설계 §6.3).
        notificationPublisher.notifyUser(
            userId = member.userId,
            type = NotificationType.GROUP_MEMBER_REMOVED,
            actorUserId = null,
            postId = access.postId,
            groupId = groupId,
            teamId = null,
            body = null,
        )
        return SearchGroupMembershipResponse.from(current)
    }

    /**
     * 개인 참여 종료.
     *
     * 판정 순서가 곧 응답 코드다.
     * 1. 그룹이 보이지 않으면 404.
     * 2. **차단이 활성이면 403** — 설계 §6.3 "차단이 활성인 동안 모든 접근 거부" 이므로 탈퇴도 막힌다.
     *    `requireRead` 를 통째로 쓰지 않는 이유는 그것이 이미 떠난 사용자(role = NONE)까지 403 으로
     *    만들어 "이미 종료한 참여의 재종료 = 409" 규칙과 어긋나기 때문이다. 차단 판정만 동일하게 적용한다.
     * 3. 직접 멤버십 행이 없으면 404 — 보호자와 팀 경유 사용자가 여기에 해당한다.
     *    팀을 통해 권한을 얻은 사용자는 이 경로로 나갈 수 없다(설계 §6.6, 팀 지원 종료로만 사라진다).
     * 4. ACTIVE/PENDING 이 아니면 409.
     */
    @Transactional
    fun leaveMe(
        groupId: UUID,
        userId: UUID,
    ): SearchGroupMembershipResponse {
        val access = accessResolver.requireVisible(groupId, userId)
        if (access.blocked) throw BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)

        val member =
            memberRepository.findByGroupIdAndUserId(groupId, userId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP)
        if (member.status != SearchGroupMemberStatus.ACTIVE && member.status != SearchGroupMemberStatus.PENDING) {
            throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }

        val now = LocalDateTime.now()
        val affected =
            memberRepository.transition(
                membershipId = member.id,
                groupId = groupId,
                expected = member.status,
                next = SearchGroupMemberStatus.LEFT,
                decidedBy = userId,
                occurredAt = now,
            )
        val current = reloadOrConflict(groupId, member.id, SearchGroupMemberStatus.LEFT, affected)
        eventRecorder.record(groupId, SearchGroupEventType.MEMBER_LEFT, userId, userId, null)
        return SearchGroupMembershipResponse.from(current)
    }

    /**
     * 참여자 목록.
     * - 보호자: PENDING 포함 전체(또는 요청한 status)
     * - 참여자: ACTIVE 목록만. status 파라미터를 보내도 무시한다.
     */
    @Transactional(readOnly = true)
    fun list(
        groupId: UUID,
        userId: UUID,
        status: SearchGroupMemberStatus? = null,
    ): List<SearchGroupMembershipResponse> {
        val access = accessResolver.requireRead(groupId, userId)
        val rows =
            if (access.isOwner) {
                if (status == null) {
                    memberRepository.findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId)
                } else {
                    memberRepository.findAllByGroupIdAndStatusOrderByCreatedAtDescIdDesc(groupId, status)
                }
            } else {
                memberRepository.findAllByGroupIdAndStatusOrderByCreatedAtDescIdDesc(
                    groupId,
                    SearchGroupMemberStatus.ACTIVE,
                )
            }
        return rows.map(SearchGroupMembershipResponse::from)
    }

    /**
     * 참여 정책 변경 (보호자 전용).
     *
     * 기존 ACTIVE 참여자는 그대로 유지하고, PENDING 을 자동 승인하지 않는다(설계 §8.3).
     * 변경 사실은 활동 기록에만 남기고 **알림은 보내지 않는다** — 설계 §8.3 이 요구하는 것은
     * 그룹 활동 기록뿐이고 §9 수신자 표에 이 항목이 없다.
     */
    @Transactional
    fun updateJoinPolicy(
        groupId: UUID,
        ownerUserId: UUID,
        joinPolicy: JoinPolicy,
    ) {
        val access = accessResolver.requireOwner(groupId, ownerUserId)
        if (access.joinPolicy == joinPolicy) return

        val affected =
            searchGroupRepository.updateJoinPolicyFrom(
                groupId = groupId,
                expected = access.joinPolicy,
                next = joinPolicy,
                activeStatus = SearchGroupStatus.ACTIVE,
                now = LocalDateTime.now(),
            )
        if (affected == 0) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)

        eventRecorder.record(
            groupId,
            SearchGroupEventType.JOIN_POLICY_CHANGED,
            ownerUserId,
            null,
            "${access.joinPolicy.name} -> ${joinPolicy.name}",
        )
    }

    private fun decide(
        groupId: UUID,
        membershipId: UUID,
        ownerUserId: UUID,
        target: SearchGroupMemberStatus,
        eventType: SearchGroupEventType,
        notificationType: NotificationType,
    ): SearchGroupMembershipResponse {
        val access = accessResolver.requireOwner(groupId, ownerUserId)
        // tuple 조회. 다른 그룹의 membershipId 는 여기서 404 로 떨어진다(설계 §16.1).
        val member =
            memberRepository.findByIdAndGroupId(membershipId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP)
        if (member.status == target) return SearchGroupMembershipResponse.from(member)
        if (member.status != SearchGroupMemberStatus.PENDING) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)

        val now = LocalDateTime.now()
        val affected =
            if (target == SearchGroupMemberStatus.ACTIVE) {
                memberRepository.activate(
                    membershipId = member.id,
                    groupId = groupId,
                    expected = SearchGroupMemberStatus.PENDING,
                    decidedBy = ownerUserId,
                    occurredAt = now,
                )
            } else {
                memberRepository.transition(
                    membershipId = member.id,
                    groupId = groupId,
                    expected = SearchGroupMemberStatus.PENDING,
                    next = target,
                    decidedBy = ownerUserId,
                    occurredAt = now,
                )
            }
        val current = reloadOrConflict(groupId, member.id, target, affected)

        eventRecorder.record(groupId, eventType, ownerUserId, member.userId, null)
        notificationPublisher.notifyUser(
            userId = member.userId,
            type = notificationType,
            actorUserId = ownerUserId,
            postId = access.postId,
            groupId = groupId,
            teamId = null,
            body = null,
        )
        return SearchGroupMembershipResponse.from(current)
    }

    /** 영향 행 0 이면 현재 상태를 재조회해 목표 상태면 멱등 성공, 아니면 409. */
    private fun reloadOrConflict(
        groupId: UUID,
        membershipId: UUID,
        target: SearchGroupMemberStatus,
        affected: Int,
    ): SearchGroupMember {
        val current =
            memberRepository.findByIdAndGroupId(membershipId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP)
        if (affected == 0 && current.status != target) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        return current
    }

    private fun joinRejection(access: GroupAccess): BusinessException =
        when {
            // 차단 사실을 응답으로 구분할 수 없게 한다. 권한 없음과 같은 403 이다.
            access.blocked -> BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)
            access.groupStatus != SearchGroupStatus.ACTIVE -> BusinessException(ErrorCode.SEARCH_ALREADY_ENDED)
            access.postStatus != MissingAnimalStatus.SEARCHING -> BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
            else -> BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }

    companion object {
        /** 설계 §20 관측 메트릭. 태그를 붙이지 않는다 — id·본문은 label 금지. */
        const val JOIN_REQUESTED_METRIC = "fmp.searchgroup.join.requested"
    }
}
