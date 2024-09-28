package com.park.animal.auth.handler

import com.park.animal.auth.SocialLoginProvider
import com.park.animal.auth.SocialLoginProvider.KAKAO
import com.park.animal.auth.dto.UserInfoDto
import com.park.animal.auth.external.KakaoFeignClient
import com.park.animal.auth.service.JwtTokenService
import com.park.animal.auth.service.UserService
import com.park.animal.common.constants.AuthConstants
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import kotlin.random.Random

@Service
class KakaoLoginHandler(
    @Value("\${auth.kakao.client-id}")
    private val clientId: String,
    @Value("\${auth.grant-type}")
    private val grantType: String,
    private val kakaoFeignClient: KakaoFeignClient,
    private val userService: UserService,
    private val jwtTokenService: JwtTokenService,
) : AbstractSocialLoginHandler(
    userService = userService,
    jwtTokenService = jwtTokenService,
) {
    override fun requestAccessToken(code: String, redirectUri: String): String {
        val token = kakaoFeignClient.getToken(
            grantType = grantType,
            clientId = clientId,
            redirectUri = redirectUri,
            code = code,
        )
        return token.accessToken
    }

    override fun isApplicable(provider: SocialLoginProvider): Boolean = provider == KAKAO

    override fun getUserInfoId(accessToken: String): UserInfoDto {
        val id = kakaoFeignClient.getTokenInfo(accessToken = AuthConstants.BEARER_PREFIX + accessToken).id.toString()
        val kakaoUserInfo = kakaoFeignClient.getKakaoUserInfo(accessToken = AuthConstants.BEARER_PREFIX + accessToken)
        return UserInfoDto(
            socialId = id,
            name = kakaoUserInfo.kakaoAccount?.name ?: "임시닉네임${Random.nextInt()}",
        )
    }
}
