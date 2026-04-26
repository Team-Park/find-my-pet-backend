package com.park.animal.bookmark

import com.park.animal.bookmark.entity.PostBookmark
import com.park.animal.bookmark.repository.PostBookmarkRepository
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.post.dto.PostSummaryResponse
import com.park.animal.post.repository.PostRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class BookmarkService(
    private val bookmarkRepository: PostBookmarkRepository,
    private val postRepository: PostRepository,
) {
    @Transactional
    fun add(
        userId: UUID,
        postId: UUID,
    ): PostBookmark {
        if (!postRepository.existsById(postId)) throw BusinessException(ErrorCode.NOT_FOUND_POST)
        bookmarkRepository.findByUserIdAndPostIdAndDeletedAtIsNull(userId, postId)?.let {
            throw BusinessException(ErrorCode.DUPLICATE_BOOKMARK)
        }
        return bookmarkRepository.save(PostBookmark(userId = userId, postId = postId))
    }

    @Transactional
    fun remove(
        userId: UUID,
        postId: UUID,
    ) {
        val b =
            bookmarkRepository.findByUserIdAndPostIdAndDeletedAtIsNull(userId, postId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_BOOKMARK)
        bookmarkRepository.delete(b)
    }

    @Transactional(readOnly = true)
    fun isBookmarked(
        userId: UUID,
        postId: UUID,
    ): Boolean = bookmarkRepository.findByUserIdAndPostIdAndDeletedAtIsNull(userId, postId) != null

    @Transactional(readOnly = true)
    fun listMyBookmarkedPosts(userId: UUID): List<PostSummaryResponse> {
        val bookmarks = bookmarkRepository.findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId)
        if (bookmarks.isEmpty()) return emptyList()
        // 단순 구현: postRepository.findById N 회. N 작을 가정. 추후 batch fetch 로 최적화 여지.
        return bookmarks.mapNotNull { b ->
            postRepository.findById(b.postId).orElse(null)?.let { post ->
                if (post.deletedAt != null) return@let null
                PostSummaryResponse(
                    id = post.id,
                    author = post.authorName,
                    title = post.title,
                    description = post.description,
                    gratuity = post.gratuity,
                    place = post.place,
                    time = post.time,
                    thumbnail = null, // 썸네일은 list-view 에서 별도 처리, 즐겨찾기 페이지에서는 우선 생략
                    missingAnimalStatus = post.missingAnimalStatus,
                    animalType = post.animalType,
                    breedId = post.breedId,
                )
            }
        }
    }

    @Transactional(readOnly = true)
    fun findBookmarkUserIdsByPost(postId: UUID): List<UUID> =
        bookmarkRepository.findAllByPostIdAndDeletedAtIsNull(postId).map { it.userId }
}
