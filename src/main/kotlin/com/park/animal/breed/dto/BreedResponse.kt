package com.park.animal.breed.dto

import com.park.animal.breed.entity.AnimalType
import com.park.animal.breed.entity.BehaviorPattern
import com.park.animal.breed.entity.Breed
import com.park.animal.breed.entity.SizeCategory
import java.util.UUID

data class BreedResponse(
    val id: UUID,
    val animalType: AnimalType,
    val nameKo: String,
    val nameEn: String?,
    val sizeCategory: SizeCategory,
    val baseSpeedKmh: Double?,
    val behaviorPattern: BehaviorPattern,
    val exploreFactor: Double,
) {
    companion object {
        fun from(entity: Breed): BreedResponse =
            BreedResponse(
                id = entity.id,
                animalType = entity.animalType,
                nameKo = entity.nameKo,
                nameEn = entity.nameEn,
                sizeCategory = entity.sizeCategory,
                baseSpeedKmh = entity.baseSpeedKmh,
                behaviorPattern = entity.behaviorPattern,
                exploreFactor = entity.exploreFactor,
            )
    }
}
