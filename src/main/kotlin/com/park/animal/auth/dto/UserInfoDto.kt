package com.park.animal.auth.dto

import com.park.animal.auth.SocialLoginProvider

data class UserInfoDto(
    val socialId: String,
    val name: String,
    val email: String?,
    val provider: SocialLoginProvider,
)
