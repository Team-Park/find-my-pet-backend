package com.park.animal.post.dto

import com.park.animal.breed.entity.AnimalType
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.entity.JoinPolicy
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
    val animalType: AnimalType,
    val breedId: UUID?,
    val applicationId: String,
    /** 직접 참여 정책. 기본값은 설계 §3-6 의 `자유롭게 참여`. */
    val joinPolicy: JoinPolicy = JoinPolicy.OPEN,
)
