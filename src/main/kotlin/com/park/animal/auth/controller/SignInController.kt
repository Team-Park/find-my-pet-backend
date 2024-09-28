package com.park.animal.auth.controller

import com.park.animal.auth.dto.JwtResponseDto
import com.park.animal.auth.dto.SignInWithSocialRequest
import com.park.animal.auth.service.SignInService
import com.park.animal.common.annotation.PublicEndPoint
import com.park.animal.common.http.response.SucceededApiResponseBody
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
    fun kakaoLogin(@RequestBody request: SignInWithSocialRequest): SucceededApiResponseBody<JwtResponseDto> {
        val response = signInService.signInWithSocial(request.toKaKaoCommand())
        return SucceededApiResponseBody(data = response)
    }

    @PostMapping("/sign-in/google")
    @PublicEndPoint
    fun signInWithGoogle(@RequestBody request: SignInWithSocialRequest): SucceededApiResponseBody<JwtResponseDto> {
        val response = signInService.signInWithSocial(request.toGoogleCommand())
        return SucceededApiResponseBody(data = response)
    }
}
