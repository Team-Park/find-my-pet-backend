package com.park.animal.auth.controller

import com.park.animal.auth.dto.JwtResponseDto
import com.park.animal.auth.dto.ReissueAccessTokenRequest
import com.park.animal.auth.dto.SignInWithSocialRequest
import com.park.animal.auth.service.SignInService
import com.park.animal.common.annotation.PublicEndPoint
import com.park.animal.common.http.response.SucceededApiResponseBody
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class SignInController(
    private val signInService: SignInService,
) {
    @PostMapping("/sign-in/kakao")
    @PublicEndPoint
    @Operation(
        summary = "kakao 소셜로그인",
        description = """
            사용자는 로그인, 회원가입시 해당 api 만 이용하면 됩니다.
            응답 값으로 반환하는 Jwt token 은 backend 에서 만든 jwt token 이며
            소셜로그인과는 무관합니다.
        """,
    )
    fun kakaoLogin(@RequestBody request: SignInWithSocialRequest): SucceededApiResponseBody<JwtResponseDto> {
        val response = signInService.signInWithSocial(request.toKaKaoCommand())
        return SucceededApiResponseBody(data = response)
    }

    @PostMapping("/sign-in/google")
    @PublicEndPoint
    @Operation(
        summary = "google 소셜로그인",
        description = """
            사용자는 로그인, 회원가입시 해당 api 만 이용하면 됩니다.
            응답 값으로 반환하는 Jwt token 은 backend 에서 만든 jwt token 이며
            소셜로그인과는 무관합니다.
        """,
    )
    fun signInWithGoogle(@RequestBody request: SignInWithSocialRequest): SucceededApiResponseBody<JwtResponseDto> {
        val response = signInService.signInWithSocial(request.toGoogleCommand())
        return SucceededApiResponseBody(data = response)
    }

    @PostMapping("/reissue")
    @PublicEndPoint
    @Operation(
        summary = "backend 서버 jwt 토큰 재발급",
        description = """
            로그인(현재는 소셜로그인) 시 발급받은 refresh Token 을 활용하여
            backend 서버의 jwt token 을 재발행 합니다.
        """,
    )
    fun reissueToken(
        @RequestBody
        request: ReissueAccessTokenRequest,
    ): SucceededApiResponseBody<JwtResponseDto> {
        val response = signInService.reissueToken(request.refreshToken)
        return SucceededApiResponseBody(data = response)
    }
}
