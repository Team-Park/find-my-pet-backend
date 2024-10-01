package com.park.animal.me

import com.park.animal.common.annotation.AuthenticationUser
import com.park.animal.common.http.response.SucceededApiResponseBody
import com.park.animal.common.resolver.UserContext
import com.park.animal.post.dto.PostSummaryResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/user")
class UserController(
    private val userService: UserService,
) {
    @GetMapping("/my-page")
    fun me(
        @AuthenticationUser
        userContext: UserContext,
    ): SucceededApiResponseBody<List<PostSummaryResponse>> {
        val response = userService.myPage(userId = userContext.userId)
        return SucceededApiResponseBody(data = response)
    }
}
