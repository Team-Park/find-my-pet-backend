package com.park.animal.breed.repository

import com.park.animal.breed.entity.AnimalType
import com.park.animal.breed.entity.Breed
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BreedRepository : JpaRepository<Breed, UUID> {
    fun findAllByAnimalTypeOrderByNameKoAsc(animalType: AnimalType): List<Breed>

    fun findAllByOrderByAnimalTypeAscNameKoAsc(): List<Breed>
}
