package com.park.animal.searchgroup.repository

import com.park.animal.searchgroup.entity.SearchGroupUserBlock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 보호자 차단 리포지토리. Task 7 이 그대로 쓴다.
 *
 * 규칙:
 * 1. delete / deleteById 금지. 차단 해제는 deactivate()(unblockedAt 채우기)이고, 재차단은
 *    reactivate()(unblockedAt 을 NULL 로 되돌리기)다 — UNIQUE(group_id,user_id) 때문에 새 행은 불가.
 * 2. reason 은 최초 차단 시 INSERT 로만 기록한다. reactivate 는 reason 을 건드리지 않아
 *    최초 차단 사유가 감사값으로 보존된다. reason 은 보호자 전용 GET /blocks 에서만 노출한다.
 * 3. 조회/전이 모두 부모 groupId 를 동반한다(설계 16.1). 비관적 락은 쓰지 않는다(F23).
 */
interface SearchGroupUserBlockRepository : JpaRepository<SearchGroupUserBlock, UUID> {
    fun findByGroupIdAndUserId(
        groupId: UUID,
        userId: UUID,
    ): SearchGroupUserBlock?

    fun findAllByGroupIdAndUnblockedAtIsNullOrderByBlockedAtDescIdDesc(groupId: UUID): List<SearchGroupUserBlock>

    fun countByGroupIdAndUserIdAndUnblockedAtIsNull(
        groupId: UUID,
        userId: UUID,
    ): Long

    /** 해제 상태(unblockedAt IS NOT NULL)인 기존 행을 다시 차단으로 되돌린다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroupUserBlock b
           SET b.blockedBy = :blockedBy,
               b.blockedAt = :occurredAt,
               b.unblockedAt = null,
               b.updatedAt = :occurredAt
         WHERE b.id = :blockId AND b.groupId = :groupId AND b.unblockedAt IS NOT NULL
        """,
    )
    fun reactivate(
        @Param("blockId") blockId: UUID,
        @Param("groupId") groupId: UUID,
        @Param("blockedBy") blockedBy: UUID,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int

    /** 차단 해제. 이미 해제된 행이면 영향 행 0 → 멱등 성공으로 해석한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroupUserBlock b
           SET b.unblockedAt = :occurredAt, b.updatedAt = :occurredAt
         WHERE b.id = :blockId AND b.groupId = :groupId AND b.unblockedAt IS NULL
        """,
    )
    fun deactivate(
        @Param("blockId") blockId: UUID,
        @Param("groupId") groupId: UUID,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int
}
