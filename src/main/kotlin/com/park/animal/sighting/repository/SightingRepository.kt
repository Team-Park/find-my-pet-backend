package com.park.animal.sighting.repository

import com.park.animal.sighting.entity.Sighting
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SightingRepository : JpaRepository<Sighting, UUID> {
    fun findAllByPostIdAndDeletedAtIsNullOrderBySightedAtAsc(postId: UUID): List<Sighting>

    fun countByPostIdAndDeletedAtIsNull(postId: UUID): Long
}
