package com.park.animal.bookmark.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.SQLDelete
import org.hibernate.type.SqlTypes
import java.util.UUID

const val POST_BOOKMARK_TABLE_NAME = "post_bookmark"

@Entity
@Table(
    name = POST_BOOKMARK_TABLE_NAME,
    uniqueConstraints = [UniqueConstraint(name = "uq_bookmark_user_post", columnNames = ["user_id", "post_id"])],
)
@SQLDelete(sql = "UPDATE $POST_BOOKMARK_TABLE_NAME SET deleted_at = NOW() WHERE id = ?")
class PostBookmark(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "post_id", nullable = false)
    val postId: UUID,
) : BaseEntity()
