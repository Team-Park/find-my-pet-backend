package com.park.animal.auth.controller

import com.park.animal.auth.dto.SignInWithSocialRequest
import com.park.animal.auth.service.SignInService
import com.park.animal.common.annotation.PublicEndPoint
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
    fun kakaoLogin(@RequestBody request: SignInWithSocialRequest) {
        signInService.signInWithSocial(request.toKaKaoCommand())
    }

    @PostMapping("/sign-in/google")
    @PublicEndPoint
    fun signInWithGoogle(@RequestBody request: SignInWithSocialRequest) {
        signInService.signInWithSocial(request.toGoogleCommand())
    }
}
