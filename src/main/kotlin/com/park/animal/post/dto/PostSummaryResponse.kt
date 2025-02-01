package com.park.animal.post.dto

import com.park.animal.post.entity.MissingAnimalStatus
import java.time.LocalDateTime
import java.util.UUID

data class PostSummaryResponse(
    val id: UUID,
    val author: String,
    val title: String,
    val description: String,
    val gratuity: Int,
    val place: String,
    val time: LocalDateTime,
    val thumbnail: String?,
    val missingAnimalStatus: MissingAnimalStatus,
)
