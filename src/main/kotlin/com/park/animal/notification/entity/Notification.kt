package com.park.animal.notification.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType.STRING
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.SQLDelete
import org.hibernate.type.SqlTypes
import java.util.UUID

const val NOTIFICATION_TABLE_NAME = "notification"

@Entity
@Table(name = NOTIFICATION_TABLE_NAME)
@SQLDelete(sql = "UPDATE $NOTIFICATION_TABLE_NAME SET deleted_at = NOW() WHERE id = ?")
class Notification(
    // 컬럼명 user_id 는 개명하지 않는다: 파생 쿼리 4개 + markAllRead JPQL + V7 인덱스 2개가 참조한다.
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", nullable = false)
    val type: NotificationType,
    @Column(name = "title", nullable = false)
    val title: String,
    @Column(name = "body")
    val body: String? = null,
    @Column(name = "link")
    val link: String? = null,
    @Column(name = "is_read", nullable = false)
    var isRead: Boolean = false,
    // --- 구조화 컨텍스트 (V13 ALTER, 설계 §9). 기존 행은 전부 NULL 이라 하위 호환. ---
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "actor_user_id")
    val actorUserId: UUID? = null,
    @Column(name = "actor_name")
    val actorName: String? = null,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "post_id")
    val postId: UUID? = null,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "group_id")
    val groupId: UUID? = null,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "team_id")
    val teamId: UUID? = null,
) : BaseEntity() {
    fun markRead() {
        this.isRead = true
    }
}
