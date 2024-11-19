package com.park.animal.post.dto

import java.time.LocalDateTime

data class PostDetailResponse(
    val author: String,
    val title: String,
    val phoneNum: String,
    val time: LocalDateTime,
    val place: String,
    val gender: String,
    val gratuity: Int,
    val description: String,
    val coordinate: Coordinate,
    val isMine: Boolean,
) {
    lateinit var imageUrls: List<PostImageResponse>
}
