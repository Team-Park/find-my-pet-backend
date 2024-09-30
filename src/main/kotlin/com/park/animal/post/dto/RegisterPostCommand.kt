package com.park.animal.post.dto

import org.springframework.web.multipart.MultipartFile
import java.time.LocalDateTime
import java.util.UUID

data class RegisterPostCommand(
    val userId: UUID,
    val images: List<MultipartFile>,
    val title: String,
    val phoneNum: String,
    val time: LocalDateTime,
    val place: String,
    val gender: String,
    val gratuity: Int,
    val description: String,
)
