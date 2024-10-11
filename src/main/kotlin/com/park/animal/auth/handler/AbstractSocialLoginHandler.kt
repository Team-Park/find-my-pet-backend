package com.park.animal.auth.handler

import com.park.animal.auth.SocialLoginProvider
import com.park.animal.auth.dto.SignInResponse
import com.park.animal.auth.dto.UserInfoDto
import com.park.animal.auth.entity.User
import com.park.animal.auth.service.JwtTokenService
import com.park.animal.auth.service.UserService
import com.park.animal.redis.RedisConstant
import com.park.animal.redis.RedisDriver
import org.springframework.stereotype.Component

@Component
abstract class AbstractSocialLoginHandler(
    private val userService: UserService,
    private val jwtTokenService: JwtTokenService,
    private val redisDriver: RedisDriver,
) {
    abstract fun requestAccessToken(code: String, redirectUri: String): String

    abstract fun isApplicable(provider: SocialLoginProvider): Boolean

    abstract fun getUserInfoId(accessToken: String): UserInfoDto

    fun handleSocialLogin(code: String, redirectUri: String, provider: SocialLoginProvider): SignInResponse {
        val accessToken = requestAccessToken(code, redirectUri)
        val userInfo = getUserInfoId(accessToken)
        val user: User = userService.findBySocialId(userInfo.socialId) ?: run {
            val saveUser = userService.saveUser(provider = provider, socialId = userInfo.socialId)
            createUserInfo(userInfo, saveUser)
            saveUser
        }
        val tokenResponse = jwtTokenService.build(user.getClaims())
        redisDriver.setValue(
            RedisConstant.REFRESH_TOKEN_PREFIX + user.id,
            tokenResponse.refreshToken,
            tokenResponse.refreshTokenExpiresIn,
        )
        return SignInResponse(
            accessToken = tokenResponse.accessToken,
            refreshToken = tokenResponse.refreshToken,
            accessTokenExpiresIn = tokenResponse.accessTokenExpiresIn,
            refreshTokenExpiresIn = tokenResponse.refreshTokenExpiresIn,
            name = userInfo.name,
            email = userInfo.email,
            provider = userInfo.provider,
        )
    }

    protected fun createUserInfo(userInfo: UserInfoDto, user: User) {
        userService.saveUserInfo(user = user, name = userInfo.name)
    }
}
