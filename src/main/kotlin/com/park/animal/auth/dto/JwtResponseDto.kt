package com.park.animal.auth.dto

data class JwtResponseDto(
    val accessToken: String,
    val refreshToken: String,
)
