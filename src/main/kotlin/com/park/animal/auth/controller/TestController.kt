package com.park.animal.auth.controller

import annotation.PublicEndPoint
import com.park.animal.auth.external.AuthGrpcService
import com.park.animal.common.interceptor.getBearerTokenFromHeader
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/test")
class TestController(
    val authGrpcService: AuthGrpcService,
) {
    @PublicEndPoint
    @PostMapping("/test")
    suspend fun test(request: HttpServletRequest) {
        authGrpcService.getUserInfo(request.getBearerTokenFromHeader())
    }
}
