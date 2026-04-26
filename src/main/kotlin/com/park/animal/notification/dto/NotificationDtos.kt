package com.park.animal.notification.dto

import com.park.animal.notification.entity.Notification
import com.park.animal.notification.entity.NotificationType
import java.time.LocalDateTime
import java.util.UUID

data class NotificationResponse(
    val id: UUID,
    val type: NotificationType,
    val title: String,
    val body: String?,
    val link: String?,
    val isRead: Boolean,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(n: Notification): NotificationResponse =
            NotificationResponse(
                id = n.id,
                type = n.type,
                title = n.title,
                body = n.body,
                link = n.link,
                isRead = n.isRead,
                createdAt = n.createdAt,
            )
    }
}

data class UnreadCountResponse(val unread: Long)
