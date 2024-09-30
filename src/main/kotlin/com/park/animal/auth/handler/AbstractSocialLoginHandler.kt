package com.park.animal.auth.handler

import com.park.animal.auth.SocialLoginProvider
import com.park.animal.auth.dto.JwtResponseDto
import com.park.animal.auth.dto.UserInfoDto
import com.park.animal.auth.entity.User
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

    abstract fun getUserInfoId(accessToken: String): UserInfoDto

    fun handleSocialLogin(code: String, redirectUri: String, provider: SocialLoginProvider): JwtResponseDto {
        val accessToken = requestAccessToken(code, redirectUri)
        val userInfo = getUserInfoId(accessToken)
        val user: User = userService.findBySocialId(userInfo.socialId) ?: run {
            userService.saveUser(provider = provider, socialId = userInfo.socialId)
        }
        createUserInfo(userInfo, user)
        return jwtTokenService.build(user.getClaims())
    }

    protected fun createUserInfo(userInfo: UserInfoDto, user: User) {
        userService.saveUserInfo(user = user, name = userInfo.name)
    }
}
