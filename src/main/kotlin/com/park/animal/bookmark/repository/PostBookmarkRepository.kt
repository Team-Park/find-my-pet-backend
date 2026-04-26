package com.park.animal.bookmark.repository

import com.park.animal.bookmark.entity.PostBookmark
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PostBookmarkRepository : JpaRepository<PostBookmark, UUID> {
    fun findByUserIdAndPostIdAndDeletedAtIsNull(
        userId: UUID,
        postId: UUID,
    ): PostBookmark?

    fun findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId: UUID): List<PostBookmark>

    fun findAllByPostIdAndDeletedAtIsNull(postId: UUID): List<PostBookmark>
}
