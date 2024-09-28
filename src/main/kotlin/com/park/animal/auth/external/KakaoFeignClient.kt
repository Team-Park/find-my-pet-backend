package com.park.animal.auth.external

import com.fasterxml.jackson.annotation.JsonProperty
import com.park.animal.common.constants.AuthConstants.AUTHORIZATION_HEADER
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam

@FeignClient(name = "kaKaoFeignClient", url = "https://kapi.kakao.com")
interface KakaoFeignClient {
    @PostMapping(path = ["/oauth/token"], consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun getToken(
        @RequestHeader(name = "Content-type")
        type: String = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        @RequestParam("grant_type")
        grantType: String,
        @RequestParam("client_id")
        clientId: String,
        @RequestParam("redirect_uri")
        redirectUri: String,
        @RequestParam("code")
        code: String,
    ): SignInKakaoResponse

    @GetMapping("/v1/user/access_token_info")
    fun getTokenInfo(
        @RequestHeader(name = AUTHORIZATION_HEADER)
        accessToken: String,
        @RequestHeader(name = "Content-type")
        type: String = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
    ): KakaoTokenResponse
}

data class SignInKakaoResponse(
    @JsonProperty("token_type")
    val tokenType: String,
    @JsonProperty("access_token")
    val accessToken: String,
    @JsonProperty("expires_in")
    val expiresIn: Int,
    @JsonProperty("refresh_token")
    val refreshToken: String,
    @JsonProperty("refresh_token_expires_in")
    val refreshTokenExpiresIn: Int,
)

data class KakaoTokenResponse(
    @JsonProperty("id")
    val id: Long,
    @JsonProperty("expires_in")
    val expiresIn: Int,
    @JsonProperty("app_id")
    val appId: Int,
)
