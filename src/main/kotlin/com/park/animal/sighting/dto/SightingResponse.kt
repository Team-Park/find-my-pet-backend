package com.park.animal.sighting.dto

import com.park.animal.sighting.entity.Sighting
import java.time.LocalDateTime
import java.util.UUID

data class SightingResponse(
    val id: UUID,
    val postId: UUID,
    val reporter: String?,
    val lat: Double,
    val lng: Double,
    val sightedAt: LocalDateTime,
    val note: String?,
    val photoUrl: String?,
    val isMine: Boolean,
) {
    companion object {
        fun from(
            entity: Sighting,
            viewerId: UUID?,
        ): SightingResponse =
            SightingResponse(
                id = entity.id,
                postId = entity.postId,
                reporter = entity.reporterName,
                lat = entity.lat,
                lng = entity.lng,
                sightedAt = entity.sightedAt,
                note = entity.note,
                photoUrl = entity.photoUrl,
                isMine = viewerId != null && viewerId == entity.reporterId,
            )
    }
}
