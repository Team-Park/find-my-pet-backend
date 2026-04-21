package com.park.animal.sighting.dto

import java.time.LocalDateTime

data class RegisterSightingRequest(
    val lat: Double,
    val lng: Double,
    val sightedAt: LocalDateTime? = null,
    val note: String? = null,
    val photoUrl: String? = null,
)
