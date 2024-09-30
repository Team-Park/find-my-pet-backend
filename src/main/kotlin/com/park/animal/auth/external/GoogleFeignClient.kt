package com.park.animal.auth.external

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam

// https://oauth2.googleapis.com
@FeignClient(name = "googleFeignClient", url = "https://www.googleapis.com")
interface GoogleFeignClient {
    @PostMapping("/token")
    fun getToken(
        @RequestHeader(name = "Content-type")
        type: String = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        @RequestBody
        request: GoogleTokenRequestDto,
    ): GoogleTokenResponseDto

    @GetMapping("/oauth2/v3/userinfo")
    fun getUserInfo(@RequestParam("access_token") accessToken: String): GoogleUserInfoDto
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class GoogleTokenRequestDto(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val grantType: String,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class GoogleTokenResponseDto(
    val accessToken: String,
    val expiresIn: Int,
    val scope: String,
    val tokenType: String,
    val refreshToken: String,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class GoogleUserInfoDto(
    val id: String,
    val email: String,
    val verifiedEmail: Boolean,
    val name: String,
    val givenName: String,
    val familyName: String,
    val picture: String,
    val locale: String,
)
