package com.park.animal.searchgroup.repository

import com.park.animal.searchgroup.entity.ArchivedReason
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroup
import com.park.animal.searchgroup.entity.SearchGroupStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 수색그룹 리포지토리. 이 시그니처 집합이 Task 3~11 의 최종 계약이다 — 이후 태스크는 이 파일을
 * 다시 쓰지 않고 그대로 쓴다.
 *
 * 규칙:
 * 1. delete / deleteById 를 절대 쓰지 않는다. 그룹의 종료는 archiveIfStatus() 상태 전이다.
 * 2. 상태 변경은 조건부 UPDATE(`WHERE id = :id AND status = :expected`)로만 노출한다.
 *    JDBC URL 에 useAffectedRows 가 없어 Connector/J 가 CLIENT_FOUND_ROWS 로 동작하므로
 *    반환값은 "매칭된 행 수" 다. 0 = 기대한 상태가 아님 → 409 로 해석한다(F13).
 * 3. 비관적 락을 쓰지 않는다(F23). 동시 종료·동시 정책변경 경합은 위 조건부 UPDATE 의
 *    영향 행 수 0/1 로 판정한다. 재조회 후 목표 상태면 멱등 성공, 아니면 409 다.
 * 4. @Modifying 메서드에 @Transactional 을 명시한다. 명시하지 않으면 SimpleJpaRepository 의
 *    클래스 레벨 @Transactional(readOnly = true) 에 걸려 read-only 커넥션에서 UPDATE 가 터진다.
 */
interface SearchGroupRepository : JpaRepository<SearchGroup, UUID> {
    fun findByIdAndDeletedAtIsNull(id: UUID): SearchGroup?

    fun findByPostIdAndDeletedAtIsNull(postId: UUID): SearchGroup?

    fun existsByPostId(postId: UUID): Boolean

    /**
     * 그룹 보관 조건부 전이. 수색 종료(FOUND)와 실종 소식 삭제(POST_DELETED) 두 경로가 공유한다.
     * archivedBy 는 시스템 전이(글 삭제 배치 등)에서 null 일 수 있다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroup g
           SET g.status = :next,
               g.archivedReason = :reason,
               g.archivedBy = :archivedBy,
               g.archivedAt = :archivedAt,
               g.updatedAt = :archivedAt
         WHERE g.id = :groupId AND g.status = :expected AND g.deletedAt IS NULL
        """,
    )
    fun archiveIfStatus(
        @Param("groupId") groupId: UUID,
        @Param("expected") expected: SearchGroupStatus,
        @Param("next") next: SearchGroupStatus,
        @Param("reason") reason: ArchivedReason,
        @Param("archivedAt") archivedAt: LocalDateTime,
        @Param("archivedBy") archivedBy: UUID?,
    ): Int

    /**
     * 참여 정책 조건부 변경. 현재 정책이 :expected 이고 그룹이 :activeStatus 일 때만 바꾼다.
     * 영향 행 0 = 다른 요청이 먼저 바꿨거나 그룹이 보관됨 → 호출부가 재조회해 멱등/409 를 가른다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE SearchGroup g
           SET g.joinPolicy = :next, g.updatedAt = :now
         WHERE g.id = :groupId
           AND g.joinPolicy = :expected
           AND g.status = :activeStatus
           AND g.deletedAt IS NULL
        """,
    )
    fun updateJoinPolicyFrom(
        @Param("groupId") groupId: UUID,
        @Param("expected") expected: JoinPolicy,
        @Param("next") next: JoinPolicy,
        @Param("activeStatus") activeStatus: SearchGroupStatus,
        @Param("now") now: LocalDateTime,
    ): Int
}
