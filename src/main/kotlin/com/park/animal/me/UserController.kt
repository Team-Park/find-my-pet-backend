package com.park.animal.me

import annotation.AuthenticationUser
import com.park.animal.auth.service.UserService
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.post.dto.PostSummaryResponse
import dto.UserContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody

@RestController
@RequestMapping("/api/v1/user")
class UserController(
    private val userService: UserService,
) {
    @GetMapping("/my-page")
    @Operation(
        summary = "마이페이지 조회",
        description = "현재는 게시글만 조회합니다",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun me(
        @Parameter(hidden = true)
        @AuthenticationUser
        userContext: UserContext,
    ): SucceededApiResponseBody<List<PostSummaryResponse>> {
        val response = userService.myPage(userId = userContext.getIdIfRequired())
        return SucceededApiResponseBody(data = response)
    }
}
