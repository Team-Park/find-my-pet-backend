package com.park.animal.post.dto

import com.park.animal.post.entity.MissingAnimalStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import java.util.UUID

data class UpdatePostRequest(
    val postId: UUID,
    val title: String,
    val phoneNum: String,
    @Schema(example = "2024-10-31T15:30:00")
    val time: LocalDateTime,
    val place: String,
    val gender: String,
    val gratuity: Int,
    val description: String,
    val lat: Double,
    val lng: Double,
    val openChatUrl: String?,
    val missingAnimalStatus: MissingAnimalStatus,
)
