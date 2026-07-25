package com.park.animal.searchgroup.repository

import com.park.animal.searchgroup.entity.SearchGroupTeam
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 팀 지원 연결 리포지토리. Task 8(팀 보관)·Task 9(지원 lifecycle)·Task 10(허브)이 그대로 쓴다.
 *
 * 규칙:
 * 1. delete / deleteById 금지 — 지원 종료도 status 전이다.
 * 2. child 조회는 부모 groupId 동반(다른 그룹의 supportId 로 접근하는 IDOR 차단, 설계 16.1).
 *    예외는 findAllByTeamIdAndStatus 하나로, 팀 보관 시 그 팀의 ACTIVE 지원 연결을 일괄
 *    WITHDRAWN 으로 회수하기 위한 팀 스코프 조회다(권한은 팀장 판정으로 이미 걸러진다).
 * 3. 동시 수락 경합은 조건부 UPDATE 의 영향 행 수 0/1 로 판정한다 — 이 레포에는 비관적 락
 *    선례가 없고 도입하지 않는다(F23).
 */
interface SearchGroupTeamRepository : JpaRepository<SearchGroupTeam, UUID> {
    fun findByGroupIdAndTeamId(
        groupId: UUID,
        teamId: UUID,
    ): SearchGroupTeam?

    fun findByIdAndGroupId(
        id: UUID,
        groupId: UUID,
    ): SearchGroupTeam?

    fun findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId: UUID): List<SearchGroupTeam>

    fun findAllByGroupIdAndStatusOrderByCreatedAtDescIdDesc(
        groupId: UUID,
        status: SearchGroupTeamStatus,
    ): List<SearchGroupTeam>

    fun findAllByTeamIdAndStatus(
        teamId: UUID,
        status: SearchGroupTeamStatus,
    ): List<SearchGroupTeam>

    fun countByGroupIdAndStatus(
        groupId: UUID,
        status: SearchGroupTeamStatus,
    ): Long

    /** 거절·철회·해제 전이. activatedAt 은 건드리지 않는다 — 활성화는 activate() 를 쓴다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroupTeam t
           SET t.status = :next,
               t.decidedBy = :decidedBy,
               t.decidedAt = :occurredAt,
               t.updatedAt = :occurredAt
         WHERE t.id = :supportId AND t.groupId = :groupId AND t.status = :expected
        """,
    )
    fun transition(
        @Param("supportId") supportId: UUID,
        @Param("groupId") groupId: UUID,
        @Param("expected") expected: SearchGroupTeamStatus,
        @Param("next") next: SearchGroupTeamStatus,
        @Param("decidedBy") decidedBy: UUID?,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int

    /** 양쪽 대기 상태(PENDING_GROUP_APPROVAL / PENDING_TEAM_APPROVAL)와 재지원이 공유하는 활성화. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroupTeam t
           SET t.status = com.park.animal.searchgroup.entity.SearchGroupTeamStatus.ACTIVE,
               t.decidedBy = :decidedBy,
               t.decidedAt = :activatedAt,
               t.activatedAt = :activatedAt,
               t.updatedAt = :activatedAt
         WHERE t.id = :supportId AND t.groupId = :groupId AND t.status = :expected
        """,
    )
    fun activate(
        @Param("supportId") supportId: UUID,
        @Param("groupId") groupId: UUID,
        @Param("expected") expected: SearchGroupTeamStatus,
        @Param("decidedBy") decidedBy: UUID?,
        @Param("activatedAt") activatedAt: LocalDateTime,
    ): Int
}
