package com.park.animal.post.dto

import org.springframework.web.multipart.MultipartFile
import java.time.LocalDateTime
import java.util.UUID

data class RegistrationPostRequest(
    val title: String,
    val phoneNum: String,
    val time: LocalDateTime,
    val place: String,
    val gender: String,
    val gratuity: Int,
    val description: String,
) {
    fun toCommand(userId: UUID, images: List<MultipartFile>): RegisterPostCommand = RegisterPostCommand(
        userId = userId,
        time = time,
        title = title,
        images = images,
        place = place,
        phoneNum = phoneNum,
        description = description,
        gratuity = gratuity,
        gender = gender,
    )
}
