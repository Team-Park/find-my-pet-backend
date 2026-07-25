package com.park.animal.notification

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.dto.NotificationResponse
import com.park.animal.notification.entity.Notification
import com.park.animal.notification.entity.NotificationType
import com.park.animal.notification.repository.NotificationRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
) {
    /**
     * 알림 1건 생성. 본인이 본인에게 보내는 알림은 [skipSelf] 가 true 면 무시 (같은 [userId]/[actorId]).
     */
    @Transactional
    fun create(
        userId: UUID,
        type: NotificationType,
        title: String,
        body: String? = null,
        link: String? = null,
        actorId: UUID? = null,
        skipSelf: Boolean = true,
    ): Notification? {
        if (skipSelf && actorId != null && actorId == userId) return null
        val n = Notification(userId = userId, type = type, title = title, body = body, link = link)
        return notificationRepository.save(n)
    }

    /** 다건(즐겨찾기 fanout 등) 생성. */
    @Transactional
    fun createMany(
        userIds: Collection<UUID>,
        type: NotificationType,
        title: String,
        body: String? = null,
        link: String? = null,
        excludeUserId: UUID? = null,
    ) {
        val targets = if (excludeUserId == null) userIds.toSet() else userIds.toSet() - excludeUserId
        if (targets.isEmpty()) return
        val list =
            targets.map { uid ->
                Notification(userId = uid, type = type, title = title, body = body, link = link)
            }
        notificationRepository.saveAll(list)
    }

    @Transactional(readOnly = true)
    fun list(
        userId: UUID,
        size: Int,
        offset: Int,
    ): List<NotificationResponse> {
        val pageSize = size.coerceAtLeast(1)
        // 정렬은 파생 쿼리명(createdAt DESC, id DESC)이 담당한다. Pageable 에 Sort 를 실으면
        // 같은 ORDER BY 절이 중복 생성된다.
        val page = PageRequest.of(offset / pageSize, pageSize)
        return notificationRepository
            .findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId, page)
            .content
            .map(NotificationResponse::from)
    }

    @Transactional(readOnly = true)
    fun unreadCount(userId: UUID): Long = notificationRepository.countByUserIdAndIsReadFalseAndDeletedAtIsNull(userId)

    @Transactional
    fun markRead(
        userId: UUID,
        notificationId: UUID,
    ) {
        val n = notificationRepository.findById(notificationId).orElseThrow { BusinessException(ErrorCode.NOT_FOUND_NOTIFICATION) }
        if (n.userId != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        n.markRead()
    }

    @Transactional
    fun markAllRead(userId: UUID): Int = notificationRepository.markAllReadByUserId(userId)
}
