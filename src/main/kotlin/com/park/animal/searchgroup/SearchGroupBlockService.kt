package com.park.animal.searchgroup

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.entity.NotificationType
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.dto.SearchGroupBlockResponse
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupUserBlock
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupUserBlockRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 보호자의 개별 사용자 차단 (설계 §6.3, §14.3).
 *
 * 차단은 허용 권한보다 우선한다. 직접 참여자뿐 아니라 팀을 통해 권한을 얻은 팀원에게도 적용되며,
 * 그 판정은 [SearchGroupAccessResolver] 의 anti-join 이 담당한다 — 이 서비스는 차단 행만 관리한다.
 *
 * `(group_id, user_id)` 가 UNIQUE 이므로 재차단은 **기존 행의 unblocked_at 을 NULL 로 되돌리는 전이**다.
 * 새 INSERT 를 시도하면 1062 가 나고, `@Transactional` 안에서 그것을 catch 하면 트랜잭션이
 * rollback-only 로 오염돼 커밋 시 500 이 된다(F14) — 그래서 자연키를 먼저 조회해 그 상황 자체를
 * 만들지 않는다(`SearchGroupMembershipService.join` 과 같은 원칙).
 *
 * **MySQL(InnoDB) REPEATABLE READ 트랩과 이 서비스의 대응.**
 * `requireOwner`(첫 plain SELECT)가 트랜잭션의 스냅샷을 고정한다. 그 뒤에 읽은 `existing` 엔티티의
 * `unblockedAt` 값은 그 스냅샷 시점 값일 뿐이라, "이미 그 상태다" 를 이 값 하나로 분기해 버리면
 * (예: `if (existing.unblockedAt != null) reactivate() 호출`) 그 사이에 경합한 다른 트랜잭션의 커밋을
 * 놓치고 **필요한 UPDATE 자체를 건너뛰는** 사고가 난다. 그래서 [block]/[unblock] 모두 조건부
 * UPDATE(`reactivate`/`deactivate`)를 행이 존재하는 한 **항상 시도**하고, 그 WHERE 절이 실행 시점의
 * 최신 커밋 값(current read)으로 스스로 판정하게 둔다 — 영향 행 0 은 그 자체로 "이미 목표 상태"
 * 라는 뜻이라 별도의 plain SELECT 재확인이 필요 없다. 감사 기록·알림은 `affected != 0`(이 호출이
 * 실제로 전이시킨 경우)에만 남겨 confirm-only 패자가 이벤트를 중복 발행하지 않게 한다.
 *
 * **reason 갱신이 엔티티 전체 저장이 아니라 컬럼 하나만 건드리는 이유.**
 * `SearchGroupUserBlockRepository.reactivate` 는 reason 을 다루지 않으므로(Task 2 계약) 서비스가
 * 반영해야 한다. 그런데 `existing`(스냅샷 값을 담은 관리 엔티티)을 그대로 mutate 해 `save`/
 * `saveAndFlush` 하면 Hibernate 는 `@DynamicUpdate` 가 없는 이 엔티티의 **모든** 매핑 컬럼을 담아
 * UPDATE 를 만든다 — 그 안의 `blockedAt`/`unblockedAt`/`blockedBy` 는 스냅샷 값이므로, 동시에
 * 경합한 다른 트랜잭션이 방금 커밋한 값을 이 저장이 덮어써 되돌리는(lost update) 사고가 날 수
 * 있다. 그래서 [entityManager] 로 `reason` 컬럼만 건드리는 targeted UPDATE 를 쓴다 — 이 UPDATE 는
 * `blockedAt`/`unblockedAt`/`blockedBy` 를 아예 SET 절에 넣지 않으므로 어떤 경합 시나리오에서도
 * 그 세 컬럼을 훼손할 수 없다.
 */
@Service
class SearchGroupBlockService(
    private val blockRepository: SearchGroupUserBlockRepository,
    private val memberRepository: SearchGroupMemberRepository,
    private val accessResolver: SearchGroupAccessResolver,
    private val eventRecorder: SearchGroupEventRecorder,
    private val notificationPublisher: GroupNotificationPublisher,
    private val entityManager: EntityManager,
) {
    @Transactional
    fun block(
        groupId: UUID,
        targetUserId: UUID,
        ownerUserId: UUID,
        reason: String?,
    ): SearchGroupBlockResponse {
        val access = accessResolver.requireOwner(groupId, ownerUserId)
        // 보호자 자신은 차단 대상이 될 수 없다. 허용하면 자기 그룹에서 스스로를 지우게 된다.
        if (targetUserId == access.ownerUserId) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)

        val now = LocalDateTime.now()
        val reasonText = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(REASON_MAX_LENGTH)
        val existing = blockRepository.findByGroupIdAndUserId(groupId, targetUserId)

        // didBlock == 이 호출이 실제로 unblocked -> blocked 전이를 일으켰는가. 감사 기록·알림은
        // 이 값에만 건다 — 이미 차단 중인 상태를 재확인만 한 호출은 아무것도 남기지 않는다.
        val didBlock: Boolean
        if (existing == null) {
            blockRepository.save(
                SearchGroupUserBlock(
                    groupId = groupId,
                    userId = targetUserId,
                    blockedBy = ownerUserId,
                    reason = reasonText,
                    blockedAt = now,
                    unblockedAt = null,
                ),
            )
            didBlock = true
        } else {
            val blockId = existing.id
            // existing.unblockedAt(스냅샷)로 미리 분기하지 않고 항상 시도한다 — WHERE 절이 실행
            // 시점의 최신 커밋 값으로 스스로 판정한다(위 클래스 KDoc 참고).
            val affected =
                blockRepository.reactivate(
                    blockId = blockId,
                    groupId = groupId,
                    blockedBy = ownerUserId,
                    occurredAt = now,
                )
            didBlock = affected != 0
            updateReasonOnly(blockId, groupId, reasonText)
        }

        // 대상의 직접 멤버십이 살아 있으면 함께 종료한다. 파생(팀) 권한은 access 쿼리가 즉시 차단한다.
        // transition() 자체가 `status = ACTIVE` 조건부라 이미 REMOVED 인 재호출에도 안전하게 0행이 된다.
        val membership = memberRepository.findByGroupIdAndUserId(groupId, targetUserId)
        if (membership != null && membership.status == SearchGroupMemberStatus.ACTIVE) {
            memberRepository.transition(
                membershipId = membership.id,
                groupId = groupId,
                expected = SearchGroupMemberStatus.ACTIVE,
                next = SearchGroupMemberStatus.REMOVED,
                decidedBy = ownerUserId,
                occurredAt = now,
            )
        }

        if (didBlock) {
            // 감사 기록에 사유를 남기지 않는다. detail 은 상태 전이 요약만 담는다.
            eventRecorder.record(groupId, SearchGroupEventType.USER_BLOCKED, ownerUserId, targetUserId, null)
            // 행위자와 사유를 대상자에게 노출하지 않는다 (설계 §6.3).
            notificationPublisher.notifyUser(
                userId = targetUserId,
                type = NotificationType.GROUP_MEMBER_BLOCKED,
                actorUserId = null,
                postId = access.postId,
                groupId = groupId,
                teamId = null,
                body = null,
            )
        }

        val saved =
            blockRepository.findByGroupIdAndUserId(groupId, targetUserId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SEARCH_GROUP)
        return SearchGroupBlockResponse.of(saved, displayNameOf(groupId, targetUserId))
    }

    /** 차단 해제. 차단 행이 없거나 이미 해제돼 있으면 아무것도 하지 않는다(멱등). */
    @Transactional
    fun unblock(
        groupId: UUID,
        targetUserId: UUID,
        ownerUserId: UUID,
    ) {
        accessResolver.requireOwner(groupId, ownerUserId)
        val existing = blockRepository.findByGroupIdAndUserId(groupId, targetUserId) ?: return

        // existing.unblockedAt(스냅샷)로 미리 분기하지 않고 항상 시도한다 — 이미 해제돼 있으면
        // WHERE b.unblockedAt IS NULL 이 실행 시점 최신 값 기준으로 스스로 0행을 반환한다.
        val affected =
            blockRepository.deactivate(
                blockId = existing.id,
                groupId = groupId,
                occurredAt = LocalDateTime.now(),
            )
        if (affected == 0) return

        eventRecorder.record(groupId, SearchGroupEventType.USER_UNBLOCKED, ownerUserId, targetUserId, null)
    }

    /**
     * 활성 차단 목록. **보호자 전용**이며 `reason` 이 노출되는 유일한 지점이다.
     *
     * 보관된 그룹에서도 보호자는 기록을 볼 수 있어야 하므로 requireOwner(410 을 던진다) 대신
     * requireVisible + 소유자 확인을 쓴다.
     *
     * 표시 이름은 멤버십 행을 한 번만 읽어 맵으로 만들어 붙인다(차단 건수만큼 조회하지 않는다).
     */
    @Transactional(readOnly = true)
    fun listBlocks(
        groupId: UUID,
        ownerUserId: UUID,
    ): List<SearchGroupBlockResponse> {
        val access = accessResolver.requireVisible(groupId, ownerUserId)
        if (!access.isOwner) throw BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)

        val nameByUserId =
            memberRepository
                .findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId)
                .associate { it.userId to it.userName }
        return blockRepository
            .findAllByGroupIdAndUnblockedAtIsNullOrderByBlockedAtDescIdDesc(groupId)
            .map { SearchGroupBlockResponse.of(it, nameByUserId[it.userId]) }
    }

    private fun displayNameOf(
        groupId: UUID,
        userId: UUID,
    ): String? = memberRepository.findByGroupIdAndUserId(groupId, userId)?.userName

    /**
     * `reason` 컬럼만 건드리는 targeted UPDATE. `SearchGroupUserBlockRepository` 를 고치지 않고도
     * (Task 2 계약 준수) 엔티티 전체를 mutate+save 할 때 생기는 lost-update 위험 없이 반영한다 —
     * 클래스 KDoc 참고.
     */
    private fun updateReasonOnly(
        blockId: UUID,
        groupId: UUID,
        reasonText: String?,
    ) {
        entityManager
            .createQuery(
                "UPDATE SearchGroupUserBlock b SET b.reason = :reason WHERE b.id = :blockId AND b.groupId = :groupId",
            ).setParameter("reason", reasonText)
            .setParameter("blockId", blockId)
            .setParameter("groupId", groupId)
            .executeUpdate()
        // 벌크 JPQL UPDATE 는 영속성 컨텍스트 1차 캐시를 갱신하지 않는다. 이후 plain SELECT
        // (block()/listBlocks() 의 재조회)가 방금 쓴 reason 을 보게 하려면 비워야 한다.
        entityManager.clear()
    }

    companion object {
        /** V12 의 `reason VARCHAR(500)` 과 맞춘다. 초과 입력은 잘라 저장한다. */
        private const val REASON_MAX_LENGTH = 500
    }
}
