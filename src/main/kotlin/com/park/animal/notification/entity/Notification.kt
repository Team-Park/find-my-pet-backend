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
) : BaseEntity() {
    fun markRead() {
        this.isRead = true
    }
}
