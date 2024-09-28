package com.park.animal.auth.handler

import com.park.animal.auth.SocialLoginProvider
import com.park.animal.auth.SocialLoginProvider.GOOGLE
import com.park.animal.auth.external.GoogleFeignClient
import com.park.animal.auth.external.GoogleTokenRequestDto
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class GoogleLoginHandler(
    @Value("\${auth.grant-type}")
    private val grantType: String,
    @Value("\${auth.google.client-id}")
    private val clientId: String,
    @Value("\${auth.google.client-secret}")
    private val clientSecret: String,
    private val googleFeignClient: GoogleFeignClient,
) : AbstractSocialLoginHandler() {
    override fun requestAccessToken(code: String, redirectUri: String): String {
        val request = GoogleTokenRequestDto(
            clientId = clientId,
            clientSecret = clientSecret,
            redirectUri = redirectUri,
            grantType = grantType,
        )
        val token = googleFeignClient.getToken(request)
        return token.accessToken
    }

    override fun isApplicable(provider: SocialLoginProvider): Boolean = provider == GOOGLE

    override fun getUserInfoId(accessToken: String): String = googleFeignClient.getUserInfo(accessToken).id
}
