package com.park.animal.post.dto

import com.park.animal.breed.entity.AnimalType
import com.park.animal.post.entity.MissingAnimalStatus
import java.time.LocalDateTime
import java.util.UUID

data class PostDetailResponse(
    val author: String,
    val title: String,
    val phoneNum: String,
    val time: LocalDateTime,
    val place: String,
    val gender: String,
    val gratuity: Int,
    val description: String,
    val coordinate: Coordinate,
    val openChatUrl: String?,
    val missingAnimalStatus: MissingAnimalStatus,
    val animalType: AnimalType,
    val breedId: UUID?,
    var isMine: Boolean,
) {
    lateinit var imageUrls: List<PostImageResponse>

    constructor(
        name: String,
        title: String,
        phoneNum: String,
        time: LocalDateTime,
        place: String,
        gender: String,
        gratuity: Int,
        description: String,
        coordinate: Coordinate,
        openChatUrl: String?,
        missingAnimalStatus: MissingAnimalStatus,
        animalType: AnimalType,
        breedId: UUID?,
        isMineInt: Int,
    ) : this(
        name,
        title,
        phoneNum,
        time,
        place,
        gender,
        gratuity,
        description,
        coordinate,
        openChatUrl,
        missingAnimalStatus,
        animalType,
        breedId,
        isMineInt == 1,
    )
}
