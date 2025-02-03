package com.park.animal.post.dto

data class ReviewDetailResponseDto(
    val author: String,
    val title: String,
    val content: String,
    var isMine: Boolean,
)
