package com.park.animal.auth.dto

import com.park.animal.auth.SocialLoginProvider

data class SignInWithSocialRequest(
    val code: String,
    val redirectUri: String,
) {
    fun toKaKaoCommand(): SignInWithSocialCommand = SignInWithSocialCommand(
        code = code,
        redirectUri = redirectUri,
        provider = SocialLoginProvider.KAKAO,
    )
    fun toGoogleCommand(): SignInWithSocialCommand = SignInWithSocialCommand(
        code = code,
        redirectUri = redirectUri,
        provider = SocialLoginProvider.GOOGLE,
    )
}
