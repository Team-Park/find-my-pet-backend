package com.park.animal.post.dto

import com.park.animal.post.entity.Review
import java.time.LocalDateTime

data class SummaryReviewResponse(
    val id: String,
    val author: String,
    val title: String,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(domain: Review): SummaryReviewResponse =
            SummaryReviewResponse(
                id = domain.id!!,
                title = domain.title,
                author = domain.authorName,
                createdAt = domain.createdAt,
            )
    }
}
