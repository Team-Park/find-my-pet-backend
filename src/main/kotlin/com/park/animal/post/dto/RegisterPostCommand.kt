package com.park.animal.post.dto

import com.park.animal.post.entity.MissingAnimalStatus
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDateTime
import java.util.UUID

data class RegisterPostCommand(
    val userId: UUID,
    val userName: String,
    val images: List<MultipartFile>,
    val title: String,
    val phoneNum: String,
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
