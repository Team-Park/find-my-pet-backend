package com.park.animal.auth.service

import com.park.animal.auth.dto.SignInResponse
import com.park.animal.auth.dto.SignInWithSocialCommand
import com.park.animal.auth.factory.SocialLoginFactory
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.redis.RedisConstant
import com.park.animal.redis.RedisDriver
import dto.JwtResponseDto
import org.springframework.stereotype.Service

@Service
class SignInService(
    private val socialLoginFactory: SocialLoginFactory,
    private val jwtTokenService: JwtTokenService,
    private val redisDriver: RedisDriver,
    private val userService: UserService,
) {
    fun signInWithSocial(command: SignInWithSocialCommand): SignInResponse {
        val providerService = socialLoginFactory.getProviderService(command.provider)
        return providerService.handleSocialLogin(
            code = command.code,
            redirectUri = command.redirectUri,
            provider = command.provider,
        )
    }

    fun reissueToken(refreshToken: String): JwtResponseDto {
        val userId = jwtTokenService.getUserIdByRefreshToken(refreshToken)
        return redisDriver.getValue(RedisConstant.REFRESH_TOKEN_PREFIX + userId, String::class.java)
            ?.let { cachingToken ->
                if (cachingToken == refreshToken) {
                    val user = userService.findById(userId)
                    jwtTokenService.build(user.getClaims())
                } else {
                    throw BusinessException(ErrorCode.REISSUE_JWT_TOKEN_FAILURE)
                }
            } ?: throw BusinessException(ErrorCode.REISSUE_JWT_TOKEN_FAILURE)
    }
}
