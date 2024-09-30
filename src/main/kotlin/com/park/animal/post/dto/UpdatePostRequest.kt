package com.park.animal.post.dto

import java.time.LocalDateTime
import java.util.UUID

data class UpdatePostRequest(
    val postId: UUID,
    val title: String,
    val phoneNum: String,
    val time: LocalDateTime,
    val place: String,
    val gender: String,
    val gratuity: Int,
    val description: String,
)
