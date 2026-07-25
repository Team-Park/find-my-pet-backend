package com.park.animal.team.repository

import com.park.animal.team.entity.Team
import com.park.animal.team.entity.TeamStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 팀 리포지토리. Task 8(팀·팀 멤버십·보관)·Task 9(지원)·Task 10(허브)이 그대로 쓴다.
 *
 * 규칙: delete / deleteById 금지 — 팀 해체는 archiveIfStatus() 로 status = ARCHIVED 전이다.
 * 공개 팀 목록은 status 로만 필터하고 개인정보를 노출하지 않는다(팀 이름/설명/인원수만).
 */
interface TeamRepository : JpaRepository<Team, UUID> {
    fun findByIdAndDeletedAtIsNull(id: UUID): Team?

    /**
     * 공개 팀 목록. [q] 는 서비스에서 trim 후 빈 문자열로 정규화해 넘긴다.
     * 빈 문자열이면 `LIKE '%%'` 가 되어 전체 활성 팀을 반환하므로 nullable 파라미터 타입 추론 문제를 피한다.
     */
    @Query(
        """
        SELECT t FROM Team t
        WHERE t.deletedAt IS NULL
          AND t.status = :status
          AND t.name LIKE CONCAT('%', :q, '%')
        ORDER BY t.name ASC
        """,
    )
    fun searchByName(
        @Param("q") q: String,
        @Param("status") status: TeamStatus,
        pageable: Pageable,
    ): Page<Team>

    fun findAllByCreatedByAndStatus(
        createdBy: UUID,
        status: TeamStatus,
    ): List<Team>

    /**
     * 팀 보관 조건부 전이(설계 6.4 — 후임 이전 대신 팀을 보관하는 경로).
     * 영향 행 0 = 이미 보관됐다 → 호출부가 멱등 성공으로 처리한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE Team t
           SET t.status = :next, t.updatedAt = :occurredAt
         WHERE t.id = :teamId AND t.status = :expected AND t.deletedAt IS NULL
        """,
    )
    fun archiveIfStatus(
        @Param("teamId") teamId: UUID,
        @Param("expected") expected: TeamStatus,
        @Param("next") next: TeamStatus,
        @Param("occurredAt") occurredAt: LocalDateTime,
    ): Int
}
