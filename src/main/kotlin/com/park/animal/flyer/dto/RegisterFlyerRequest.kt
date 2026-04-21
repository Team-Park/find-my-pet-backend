package com.park.animal.flyer.dto

import com.park.animal.flyer.entity.FlyerVisibility

data class RegisterFlyerRequest(
    val lat: Double,
    val lng: Double,
    val note: String? = null,
    val visibility: FlyerVisibility = FlyerVisibility.PRIVATE,
)
