package com.park.animal.notification.dto

import com.park.animal.notification.entity.Notification
import com.park.animal.notification.entity.NotificationType
import java.time.LocalDateTime
import java.util.UUID

/**
 * 필드 추가는 프론트에 하위호환이다(기존 필드는 이름·타입 그대로).
 * 프론트는 link 대신 postId/groupId/teamId 로 목적 화면을 조립할 수 있다.
 */
data class NotificationResponse(
    val id: UUID,
    val type: NotificationType,
    val title: String,
    val body: String?,
    val link: String?,
    val isRead: Boolean,
    val createdAt: LocalDateTime,
    val actorName: String?,
    val postId: UUID?,
    val groupId: UUID?,
    val teamId: UUID?,
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
                actorName = n.actorName,
                postId = n.postId,
                groupId = n.groupId,
                teamId = n.teamId,
            )
    }
}

data class UnreadCountResponse(val unread: Long)
