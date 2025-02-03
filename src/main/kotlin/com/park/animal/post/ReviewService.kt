package com.park.animal.post

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.post.dto.ReviewDetailResponseDto
import com.park.animal.post.dto.SummarizedPostsByPageQuery
import com.park.animal.post.dto.SummaryReviewResponse
import com.park.animal.post.entity.Review
import com.park.animal.post.repository.ReviewRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ReviewService(
    val reviewRepository: ReviewRepository,
) {
    @Transactional
    fun registerReview(
        userId: String,
        authorName: String,
        title: String,
        content: String,
        categoryId: String,
    ) {
        val review =
            Review.create(
                authorName = authorName,
                authorId = userId,
                title = title,
                content = content,
                categoryId = categoryId,
                fields = emptyMap(),
            )
        reviewRepository.save(review)
    }

    private fun getEntity(id: String): Review =
        reviewRepository.findById(id).orElseThrow {
            BusinessException(ErrorCode.NOT_FOUND_REVIEW)
        }

    @Transactional(readOnly = true)
    fun getDetail(
        id: String,
        userId: UUID?,
    ): ReviewDetailResponseDto {
        val review = getEntity(id)
        return ReviewDetailResponseDto(
            author = review.authorName,
            title = review.title,
            content = review.content,
            isMine = userId == UUID.fromString(review.authorId),
        )
    }

    @Transactional(readOnly = true)
    fun findReviewList(query: SummarizedPostsByPageQuery): Page<SummaryReviewResponse> {
        val page = PageRequest.of(query.offset.toInt(), query.size.toInt(), query.orderBy.toSort())
        return reviewRepository.findAll(page).map {
            SummaryReviewResponse.from(it)
        }
    }
}
