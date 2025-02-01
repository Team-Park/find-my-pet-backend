package com.park.animal.post.dto

import java.util.UUID

data class PostImageResponse(
    val id: UUID,
    val image: String,
)
