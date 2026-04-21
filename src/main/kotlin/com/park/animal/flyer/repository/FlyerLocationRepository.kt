package com.park.animal.flyer.repository

import com.park.animal.flyer.entity.FlyerLocation
import com.park.animal.flyer.entity.FlyerVisibility
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface FlyerLocationRepository : JpaRepository<FlyerLocation, UUID> {
    fun findAllByPostIdAndDeletedAtIsNullOrderByPostedAtAsc(postId: UUID): List<FlyerLocation>

    fun findAllByPostIdAndVisibilityAndDeletedAtIsNullOrderByPostedAtAsc(
        postId: UUID,
        visibility: FlyerVisibility,
    ): List<FlyerLocation>

    fun countByPostIdAndDeletedAtIsNull(postId: UUID): Long
}
