package com.park.animal.auth.dto

data class ReissueAccessTokenRequest(
    val refreshToken: String,
)
