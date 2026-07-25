package com.park.animal.notification.repository

import com.park.animal.notification.entity.Notification
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface NotificationRepository : JpaRepository<Notification, UUID> {
    /**
     * 알림 목록. created_at 은 V7 에서 초 정밀도 TIMESTAMP 라 동일 초 다건이 흔하고
     * (함께 찾기는 한 액션에서 그룹 전체에 fanout 한다) 목록은 offset 페이지네이션이다.
     * createdAt 단독 정렬이면 페이지 경계에서 중복/누락이 나므로 id DESC 를 tiebreaker 로 둔다(F20).
     *
     * 호출 측 규칙 두 가지.
     * 1. Pageable 에 Sort 를 넣지 않는다 — 메서드명 정렬과 중복되어 ORDER BY 가 두 번 붙는다.
     * 2. 이 이름(`...OrderByCreatedAtDescIdDesc`)이 계약이다. 알림 목록을 읽는 모든 코드·테스트가
     *    이 이름을 쓴다. 옛 이름 `...OrderByCreatedAtDesc` 는 더 이상 존재하지 않는다.
     */
    fun findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
        userId: UUID,
        pageable: Pageable,
    ): Page<Notification>

    fun countByUserIdAndIsReadFalseAndDeletedAtIsNull(userId: UUID): Long

    @Modifying
    @Query(
        """
        UPDATE Notification n
        SET n.isRead = true
        WHERE n.userId = :userId AND n.isRead = false AND n.deletedAt IS NULL
        """,
    )
    fun markAllReadByUserId(
        @Param("userId") userId: UUID,
    ): Int
}
