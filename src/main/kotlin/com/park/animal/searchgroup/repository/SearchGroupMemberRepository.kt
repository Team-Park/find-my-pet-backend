package com.park.animal.searchgroup.repository

import com.park.animal.searchgroup.entity.SearchGroupMember
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 직접 참여 멤버십 리포지토리. Task 6(참여 lifecycle)·Task 7(차단)·Task 10(허브)이 그대로 쓴다.
 *
 * 규칙:
 * 1. delete / deleteById 를 절대 쓰지 않는다. 탈퇴·내보내기·재가입은 전부 status 전이다(F15).
 * 2. child 조회는 반드시 부모 groupId 를 함께 받는다. membershipId 단독 조회 후 그룹 권한을
 *    따로 확인하는 형태는 IDOR 을 부른다(설계 16.1). findById 는 쓰지 않는다.
 * 3. 목록 정렬에는 createdAt 뒤에 id tiebreaker 를 둔다 — 동일 시각 다건에서 순서가 흔들린다(F20).
 *    phase 1 멤버십 목록에는 페이징이 없으므로 Pageable 을 받지 않고 List 를 그대로 돌려준다.
 * 4. 비관적 락을 쓰지 않는다(F23). 동시 승인/탈퇴 경합은 transition()/activate() 의
 *    `AND m.status = :expected` 가 직렬화한다 — 둘 중 하나만 1 을 받는다.
 */
interface SearchGroupMemberRepository : JpaRepository<SearchGroupMember, UUID> {
    /** 자연키 조회. 재가입은 이 행을 찾아 status 를 전이시킨다(새 INSERT 금지). */
    fun findByGroupIdAndUserId(
        groupId: UUID,
        userId: UUID,
    ): SearchGroupMember?

    fun findByIdAndGroupId(
        id: UUID,
        groupId: UUID,
    ): SearchGroupMember?

    fun findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId: UUID): List<SearchGroupMember>

    fun findAllByGroupIdAndStatusOrderByCreatedAtDescIdDesc(
        groupId: UUID,
        status: SearchGroupMemberStatus,
    ): List<SearchGroupMember>

    fun findAllByUserIdAndStatus(
        userId: UUID,
        status: SearchGroupMemberStatus,
    ): List<SearchGroupMember>

    fun countByGroupIdAndStatus(
        groupId: UUID,
        status: SearchGroupMemberStatus,
    ): Long

    /**
     * 조건부 상태 전이(승인 거절·탈퇴·내보내기·요청 접수).
     * joinedAt 은 건드리지 않는다 — ACTIVE 로 올리는 전이는 activate() 를 쓴다.
     * 영향 행 0 = 이미 다른 상태이거나 다른 그룹의 멤버십 id 다 → 호출부가 재조회해 멱등/409 를 가른다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroupMember m
           SET m.status = :next,
               m.decidedAt = :occurredAt,
               m.decidedBy = :decidedBy,
               m.updatedAt = :occurredAt
         WHERE m.id = :membershipId AND m.groupId = :groupId AND m.status = :expected
        """,
    )
    fun transition(
        @Param("membershipId") membershipId: UUID,
        @Param("groupId") groupId: UUID,
        @Param("expected") expected: SearchGroupMemberStatus,
        @Param("next") next: SearchGroupMemberStatus,
        @Param("decidedBy") decidedBy: UUID?,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int

    /** PENDING 승인과 LEFT/REMOVED/REJECTED 재가입에 공통으로 쓴다. 같은 행을 되살린다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroupMember m
           SET m.status = com.park.animal.searchgroup.entity.SearchGroupMemberStatus.ACTIVE,
               m.joinedAt = :occurredAt,
               m.decidedAt = :occurredAt,
               m.decidedBy = :decidedBy,
               m.updatedAt = :occurredAt
         WHERE m.id = :membershipId AND m.groupId = :groupId AND m.status = :expected
        """,
    )
    fun activate(
        @Param("membershipId") membershipId: UUID,
        @Param("groupId") groupId: UUID,
        @Param("expected") expected: SearchGroupMemberStatus,
        @Param("decidedBy") decidedBy: UUID?,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int
}
