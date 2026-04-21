package com.park.animal.breed

import com.park.animal.breed.dto.BreedResponse
import com.park.animal.breed.entity.AnimalType
import com.park.animal.breed.entity.Breed
import com.park.animal.breed.repository.BreedRepository
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class BreedService(
    val breedRepository: BreedRepository,
) {
    @Transactional(readOnly = true)
    fun findAll(animalType: AnimalType?): List<BreedResponse> {
        val breeds =
            if (animalType != null) {
                breedRepository.findAllByAnimalTypeOrderByNameKoAsc(animalType)
            } else {
                breedRepository.findAllByOrderByAnimalTypeAscNameKoAsc()
            }
        return breeds.map { BreedResponse.from(it) }
    }

    @Transactional(readOnly = true)
    fun findById(id: UUID): BreedResponse {
        val breed = getBreed(id)
        return BreedResponse.from(breed)
    }

    @Transactional(readOnly = true)
    fun getBreed(id: UUID): Breed =
        breedRepository
            .findById(id)
            .orElseThrow { BusinessException(ErrorCode.NOT_FOUND_BREED) }
}
