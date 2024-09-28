package com.park.animal.post.dto

import java.time.LocalDateTime

data class PostDetailResponse(
    val author: String,
    val imageUrls: List<String>,
    val title: String,
    val phoneNum: String,
    val time: LocalDateTime,
    val place: String,
    val gender: String,
    val gratuity: Int,
    val description: String,
)
