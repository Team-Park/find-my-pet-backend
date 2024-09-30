package com.park.animal.post.dto

import java.util.UUID

data class DeletePostImageRequest(
    val postId: UUID,
    val postImageId: UUID,
)
