package com.park.animal.auth.external

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.park.animal.common.constants.AuthConstants.AUTHORIZATION_HEADER
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDateTime

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

    @GetMapping(path = ["/v2/user/me"], consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun getKakaoUserInfo(
        @RequestHeader(name = "Content-type")
        type: String = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        @RequestHeader(name = AUTHORIZATION_HEADER)
        accessToken: String,
    ): KakaoUserInfoResponse
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

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class KakaoUserInfoResponse(
    val id: Long,
    val hasSignedUp: Boolean?,
    val connectedAt: LocalDateTime?,
    val synchedAt: LocalDateTime?,
    val properties: Map<String, String>?,
    val kakaoAccount: KakaoAccount?,
    val forPartner: Partner?,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class KakaoAccount(
    val profileNeedsAgreement: Boolean?,
    val profileNicknameNeedsAgreement: Boolean?,
    val profileImageNeedsAgreement: Boolean?,
    val profile: Profile?,
    val nameNeedsAgreement: Boolean?,
    val name: String?,
    val emailNeedsAgreement: Boolean?,
    val isEmailValid: Boolean?,
    val isEmailVerified: Boolean?,
    val email: String?,
    val ageRangeNeedsAgreement: Boolean?,
    val ageRange: String?,
    val birthyearNeedsAgreement: Boolean?,
    val birthyear: String?,
    val birthdayNeedsAgreement: Boolean?,
    val birthday: String?,
    val birthdayType: String?,
    val genderNeedsAgreement: Boolean?,
    val gender: String?,
    val phoneNumberNeedsAgreement: Boolean?,
    val phoneNumber: String?,
    val ciNeedsAgreement: Boolean?,
    val ci: String?,
    val ciAuthenticatedAt: LocalDateTime?,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Profile(
    val nickname: String?,
    val thumbnailImageUrl: String?,
    val profileImageUrl: String?,
    val isDefaultImage: Boolean?,
    val isDefaultNickname: Boolean?,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Partner(
    val uuid: String?,
)
