package com.park.animal.auth.dto

import com.park.animal.auth.SocialLoginProvider
import java.util.UUID

data class SignInResponse(
    val id: UUID,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresIn: Long,
    val refreshTokenExpiresIn: Long,
    val name: String,
    val provider: SocialLoginProvider,
    val email: String?,
)
