package com.park.animal.auth.handler

import com.park.animal.auth.SocialLoginProvider
import com.park.animal.auth.SocialLoginProvider.GOOGLE
import com.park.animal.auth.dto.UserInfoDto
import com.park.animal.auth.external.GoogleFeignClient
import com.park.animal.auth.external.GoogleTokenRequestDto
import com.park.animal.auth.service.JwtTokenService
import com.park.animal.auth.service.UserService
import com.park.animal.common.constants.AuthConstants
import com.park.animal.redis.RedisDriver
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
    private val userService: UserService,
    private val jwtTokenService: JwtTokenService,
    private val redisDriver: RedisDriver,
) : AbstractSocialLoginHandler(
    userService = userService,
    jwtTokenService = jwtTokenService,
    redisDriver = redisDriver,
) {
    override fun requestAccessToken(code: String, redirectUri: String): String {
        val request = GoogleTokenRequestDto(
            clientId = clientId,
            clientSecret = clientSecret,
            redirectUri = redirectUri,
            grantType = grantType,
        )
        val token = googleFeignClient.getToken(request = request)
        return token.accessToken
    }

    override fun isApplicable(provider: SocialLoginProvider): Boolean = provider == GOOGLE

    override fun getUserInfoId(accessToken: String): UserInfoDto {
        return googleFeignClient.getUserInfo(accessToken = AuthConstants.BEARER_PREFIX + accessToken).let {
            UserInfoDto(
                socialId = it.id,
                name = it.name,
                email = it.email,
                provider = GOOGLE,
            )
        }
    }
}
