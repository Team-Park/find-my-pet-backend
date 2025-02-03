package com.park.animal.post.dto

import jakarta.annotation.Nullable

data class RegisterReviewRequest(
    val title: String,
    val content: String,
    @Nullable
    val category: String?,
)
