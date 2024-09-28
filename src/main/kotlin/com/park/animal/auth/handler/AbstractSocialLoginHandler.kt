package com.park.animal.auth.handler

import com.park.animal.auth.SocialLoginProvider
import com.park.animal.auth.User
import com.park.animal.auth.dto.JwtResponseDto
import com.park.animal.auth.service.JwtTokenService
import com.park.animal.auth.service.UserService
import org.springframework.stereotype.Component

@Component
abstract class AbstractSocialLoginHandler(
    private val userService: UserService,
    private val jwtTokenService: JwtTokenService,
) {
    abstract fun requestAccessToken(code: String, redirectUri: String): String

    abstract fun isApplicable(provider: SocialLoginProvider): Boolean

    abstract fun getUserInfoId(accessToken: String): String

    fun handleSocialLogin(code: String, redirectUri: String, provider: SocialLoginProvider): JwtResponseDto {
        val accessToken = requestAccessToken(code, redirectUri)
        val userInfoId = getUserInfoId(accessToken)
        val user: User = userService.findBySocialId(userInfoId) ?: run {
            userService.saveUser(provider = provider, socialId = userInfoId)
        }
        return jwtTokenService.build(user.getClaims())
    }
}
