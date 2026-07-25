package com.park.animal.team.repository

import com.park.animal.team.entity.TeamMember
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 팀 멤버십 리포지토리. Task 5(팀원 fan-out)·Task 8(팀 멤버십/팀장 이전/보관)·Task 9(팀장 판정)·
 * Task 10(허브)이 그대로 쓴다.
 *
 * 규칙:
 * 1. delete / deleteById 금지 — 탈퇴·내보내기·재가입 전부 status 전이다(F15).
 * 2. child 조회는 부모 teamId 동반(설계 16.1). findById 는 쓰지 않는다.
 * 3. 팀장 이전은 changeRole() 을 한 트랜잭션 안에서 두 번 호출한다 —
 *    먼저 현 팀장 LEADER→MEMBER, 그 다음 대상 MEMBER→LEADER. 순서를 바꾸거나 단일
 *    CASE WHEN UPDATE 로 합치면 uq_tm_single_active_leader 가 행 단위로 검사돼 1062 가 난다.
 * 4. 탈퇴/내보내기 시에는 role 을 MEMBER 로 되돌린 뒤 status 를 바꾼다 — LEADER/LEFT 조합은
 *    generated key 가 NULL 이라 제약에는 걸리지 않지만 이력이 헷갈린다.
 * 5. 비관적 락을 쓰지 않는다(F23). 두 팀장 후보가 동시에 승격되는 경합은 조건부 UPDATE 와
 *    uq_tm_single_active_leader 가 함께 막는다.
 * 6. findAllByTeamIdAndStatus 는 Task 5 의 GroupNotificationPublisher.notifyTeamMembers 가
 *    수신자(ACTIVE 팀원)를 계산할 때 쓴다 — 정렬이 필요 없어 별도 orderBy 를 두지 않는다.
 */
interface TeamMemberRepository : JpaRepository<TeamMember, UUID> {
    fun findByTeamIdAndUserId(
        teamId: UUID,
        userId: UUID,
    ): TeamMember?

    fun findByIdAndTeamId(
        id: UUID,
        teamId: UUID,
    ): TeamMember?

    /** 활성 팀장. 유일성은 uq_tm_single_active_leader 가 DB 에서 보장하지만, 전이 도중 조회를 견디도록 First 를 쓴다. */
    fun findFirstByTeamIdAndRoleAndStatus(
        teamId: UUID,
        role: TeamRole,
        status: TeamMemberStatus,
    ): TeamMember?

    fun findAllByTeamIdAndStatus(
        teamId: UUID,
        status: TeamMemberStatus,
    ): List<TeamMember>

    fun findAllByTeamIdOrderByCreatedAtDescIdDesc(teamId: UUID): List<TeamMember>

    fun findAllByUserIdAndStatus(
        userId: UUID,
        status: TeamMemberStatus,
    ): List<TeamMember>

    fun countByTeamIdAndStatus(
        teamId: UUID,
        status: TeamMemberStatus,
    ): Long

    /** 거절·탈퇴·내보내기 전이. joinedAt 은 건드리지 않는다 — 활성화는 activate() 를 쓴다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE TeamMember m
           SET m.status = :next,
               m.decidedAt = :occurredAt,
               m.decidedBy = :decidedBy,
               m.updatedAt = :occurredAt
         WHERE m.id = :membershipId AND m.teamId = :teamId AND m.status = :expected
        """,
    )
    fun transition(
        @Param("membershipId") membershipId: UUID,
        @Param("teamId") teamId: UUID,
        @Param("expected") expected: TeamMemberStatus,
        @Param("next") next: TeamMemberStatus,
        @Param("decidedBy") decidedBy: UUID?,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int

    /** PENDING 승인과 LEFT/REMOVED/REJECTED 재가입에 공통으로 쓴다. 같은 행을 되살린다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE TeamMember m
           SET m.status = com.park.animal.team.entity.TeamMemberStatus.ACTIVE,
               m.joinedAt = :occurredAt,
               m.decidedAt = :occurredAt,
               m.decidedBy = :decidedBy,
               m.updatedAt = :occurredAt
         WHERE m.id = :membershipId AND m.teamId = :teamId AND m.status = :expected
        """,
    )
    fun activate(
        @Param("membershipId") membershipId: UUID,
        @Param("teamId") teamId: UUID,
        @Param("expected") expected: TeamMemberStatus,
        @Param("decidedBy") decidedBy: UUID?,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int

    /**
     * 팀장 이전용 역할 전이. 활성 멤버에게만 적용된다.
     * 강등(LEADER→MEMBER)을 먼저, 승격(MEMBER→LEADER)을 나중에 호출한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE TeamMember m
           SET m.role = :next, m.updatedAt = :occurredAt
         WHERE m.id = :membershipId AND m.teamId = :teamId AND m.role = :expected
           AND m.status = com.park.animal.team.entity.TeamMemberStatus.ACTIVE
        """,
    )
    fun changeRole(
        @Param("membershipId") membershipId: UUID,
        @Param("teamId") teamId: UUID,
        @Param("expected") expected: TeamRole,
        @Param("next") next: TeamRole,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int
}
