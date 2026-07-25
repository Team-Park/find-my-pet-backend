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
     *
     * 기존 호출부 계약 유지용 얇은 래퍼다. 신규 코드는 [createStructured] 를 쓴다.
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
    ): Notification? =
        createStructured(
            userId = userId,
            type = type,
            title = title,
            body = body,
            link = link,
            actorUserId = actorId,
            skipSelf = skipSelf,
        )

    /** 다건(즐겨찾기 fanout 등) 생성. 신규 코드는 [createStructuredMany] 를 쓴다. */
    @Transactional
    fun createMany(
        userIds: Collection<UUID>,
        type: NotificationType,
        title: String,
        body: String? = null,
        link: String? = null,
        excludeUserId: UUID? = null,
    ) = createStructuredMany(
        userIds = userIds,
        type = type,
        title = title,
        body = body,
        link = link,
        excludeUserId = excludeUserId,
    )

    /** 구조화 컨텍스트를 포함한 알림 1건 생성 (설계 §9). */
    @Transactional
    fun createStructured(
        userId: UUID,
        type: NotificationType,
        title: String,
        body: String? = null,
        link: String? = null,
        actorUserId: UUID? = null,
        actorName: String? = null,
        postId: UUID? = null,
        groupId: UUID? = null,
        teamId: UUID? = null,
        skipSelf: Boolean = true,
    ): Notification? {
        if (skipSelf && actorUserId != null && actorUserId == userId) return null
        return notificationRepository.save(
            Notification(
                userId = userId,
                type = type,
                title = title,
                body = body,
                link = link,
                actorUserId = actorUserId,
                actorName = actorName,
                postId = postId,
                groupId = groupId,
                teamId = teamId,
            ),
        )
    }

    /**
     * 구조화 컨텍스트를 포함한 다건 생성. 수신자 중복 제거는 호출부(GroupNotificationPublisher)가
     * 이미 마쳤지만, 방어적으로 여기서도 Set 으로 좁힌다.
     */
    @Transactional
    fun createStructuredMany(
        userIds: Collection<UUID>,
        type: NotificationType,
        title: String,
        body: String? = null,
        link: String? = null,
        actorUserId: UUID? = null,
        actorName: String? = null,
        postId: UUID? = null,
        groupId: UUID? = null,
        teamId: UUID? = null,
        excludeUserId: UUID? = null,
    ) {
        val targets = if (excludeUserId == null) userIds.toSet() else userIds.toSet() - excludeUserId
        if (targets.isEmpty()) return
        notificationRepository.saveAll(
            targets.map { uid ->
                Notification(
                    userId = uid,
                    type = type,
                    title = title,
                    body = body,
                    link = link,
                    actorUserId = actorUserId,
                    actorName = actorName,
                    postId = postId,
                    groupId = groupId,
                    teamId = teamId,
                )
            },
        )
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
