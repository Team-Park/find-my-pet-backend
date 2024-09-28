package com.park.animal.post

import com.park.animal.common.annotation.AuthenticationUser
import com.park.animal.common.annotation.PublicEndPoint
import com.park.animal.common.http.response.PaginatedApiResponseDto
import com.park.animal.common.http.response.SucceededApiResponseBody
import com.park.animal.common.resolver.UserContext
import com.park.animal.post.dto.PostDetailResponse
import com.park.animal.post.dto.PostSummaryResponse
import com.park.animal.post.dto.RegisterPostCommand
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDateTime
import java.util.*

@RestController
@RequestMapping("/api/v1")
class PostController(
    private val postService: PostService,
) {
    @GetMapping("/posts")
    fun getPosts(

    ): PaginatedApiResponseDto<PostSummaryResponse> {
        throw RuntimeException()
    }

    @GetMapping("/post/{id}")
    fun getPost(@PathVariable id: UUID): SucceededApiResponseBody<PostDetailResponse> {
        TODO()
    }

    @PostMapping(path = ["/post"], consumes = ["multipart/form-data", "application/json"])
    @PublicEndPoint
    suspend fun registerPost(
        @AuthenticationUser
        userContext: UserContext,
        @RequestParam title: String,
        @RequestParam phoneNum: String,
        @RequestParam time: LocalDateTime,
        @RequestParam place: String,
        @RequestParam gender: String,
        @RequestParam gratuity: Int,
        @RequestParam description: String,
        @RequestParam image: List<MultipartFile>,
    ): SucceededApiResponseBody<Void> {
        postService.registerPost(
            RegisterPostCommand(
                userId = userContext.userId,
                images = image,
                title = title,
                phoneNum = phoneNum,
                time = time,
                place = place,
                gender = gender,
                gratuity = gratuity,
                description = description,
            ),
        )
        return SucceededApiResponseBody(data = null)
    }
}
