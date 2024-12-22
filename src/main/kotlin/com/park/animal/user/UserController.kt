package com.park.animal.user

import annotation.AuthenticationUser
import com.park.animal.common.config.SwaggerConfig
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
class UserController {
    @GetMapping("/me")
    @Operation(
        summary = "사용자 정보 조회",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    suspend fun me(
        @AuthenticationUser
        @Parameter(hidden = true)
        userContext: UserContext,
    ): SucceededApiResponseBody<UserInfoResponse> {
        val response =
            UserInfoResponse(
                email = userContext.getEmailIfRequired(),
                role = userContext.getRoleIfRequired().name,
                name = userContext.getNameIfRequired(),
            )

        return SucceededApiResponseBody(response)
    }
}
