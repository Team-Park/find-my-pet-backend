package com.park.animal.flyer.dto

import com.park.animal.flyer.entity.FlyerLocation
import com.park.animal.flyer.entity.FlyerStatus
import com.park.animal.flyer.entity.FlyerVisibility
import java.time.LocalDateTime
import java.util.UUID

data class FlyerLocationResponse(
    val id: UUID,
    val postId: UUID,
    val lat: Double,
    val lng: Double,
    val note: String?,
    val status: FlyerStatus,
    val visibility: FlyerVisibility,
    val postedAt: LocalDateTime,
    val collectedAt: LocalDateTime?,
) {
    companion object {
        fun from(entity: FlyerLocation): FlyerLocationResponse =
            FlyerLocationResponse(
                id = entity.id,
                postId = entity.postId,
                lat = entity.lat,
                lng = entity.lng,
                note = entity.note,
                status = entity.status,
                visibility = entity.visibility,
                postedAt = entity.postedAt,
                collectedAt = entity.collectedAt,
            )
    }
}
