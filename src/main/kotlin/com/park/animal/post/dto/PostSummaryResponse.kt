package com.park.animal.post.dto

import java.time.LocalDateTime

data class PostSummaryResponse(
    val author: String,
    val title: String,
    val gratuity: Int,
    val place: String,
    val time: LocalDateTime,
    val thumbnail: String?,
)
