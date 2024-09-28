package com.park.animal.auth.dto

import com.park.animal.auth.SocialLoginProvider

data class SignInWithSocialCommand(
    val code: String,
    val redirectUri: String,
    val provider: SocialLoginProvider,
)
