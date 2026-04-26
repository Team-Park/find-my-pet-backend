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
    fun findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
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
