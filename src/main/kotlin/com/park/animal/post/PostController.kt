package com.park.animal.post

import annotation.AuthenticationUser
import annotation.PublicEndPoint
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.common.constants.OrderBy
import com.park.animal.post.dto.DeletePostImageRequest
import com.park.animal.post.dto.PostDetailResponse
import com.park.animal.post.dto.PostSummaryResponse
import com.park.animal.post.dto.RegisterPostCommand
import com.park.animal.post.dto.SummarizedPostsByPageQuery
import com.park.animal.post.dto.UpdatePostRequest
import dto.UserContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import model.Role
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.woo.http.PaginatedApiResponseBody
import org.woo.http.PaginatedApiResponseDto
import org.woo.http.SucceededApiResponseBody
import java.time.LocalDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class PostController(
    private val postService: PostService,
) {
    @GetMapping("/posts")
    @PublicEndPoint
    @Operation(
        summary = "게시글 페이지네이션 조회",
    )
    fun getPosts(
        @RequestParam(name = "pageSize", required = false, defaultValue = "20") size: Long,
        @RequestParam(name = "pageOffset", required = false, defaultValue = "0") offset: Long,
        @RequestParam(name = "orderBy", required = false, defaultValue = "CREATED_AT_DESC") orderBy: OrderBy,
    ): PaginatedApiResponseBody<PostSummaryResponse> {
        val query: SummarizedPostsByPageQuery =
            SummarizedPostsByPageQuery(
                size = size,
                offset = offset,
                orderBy = orderBy,
            )
        val response = postService.findPostList(query)
        return PaginatedApiResponseBody(
            data =
                PaginatedApiResponseDto(
                    contents = response.result,
                    hasNextPage = response.hasNextPage,
                    totalCount = response.totalCount,
                ),
        )
    }

    @GetMapping("/post/{id}")
    @PublicEndPoint
    fun getPost(
        @PathVariable id: UUID,
        @AuthenticationUser(isRequired = false)
        @Parameter(hidden = true)
        userContext: UserContext?,
    ): SucceededApiResponseBody<PostDetailResponse> {
        val response = postService.findDetailPost(id, userContext?.userId)
        return SucceededApiResponseBody(data = response)
    }

    @PostMapping(path = ["/post"], consumes = ["multipart/form-data", "application/json"])
    @Operation(
        summary = "게시글 등록 API",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    suspend fun registerPost(
        @AuthenticationUser
        @Parameter(hidden = true)
        userContext: UserContext,
        @RequestParam title: String,
        @RequestParam phoneNum: String,
        @RequestParam
        @Parameter(example = "2000-10-31T01:30:00")
        time: LocalDateTime,
        @RequestParam place: String,
        @RequestParam gender: String,
        @RequestParam(required = false, defaultValue = "0") gratuity: Int,
        @RequestParam description: String,
        @RequestParam(required = false) image: List<MultipartFile> = emptyList(),
        @RequestParam lat: Double,
        @RequestParam lng: Double,
        @RequestParam openChatUrl: String?,
        @RequestParam(required = false) customNickname: String?,
    ): SucceededApiResponseBody<Void> {
        val command =
            if (customNickname != null && userContext.role == Role.ROLE_ADMIN) {
                RegisterPostCommand(
                    userId = userContext.getIdIfRequired(),
                    userName = customNickname,
                    images = image,
                    title = title,
                    phoneNum = phoneNum,
                    time = time,
                    place = place,
                    gender = gender,
                    gratuity = gratuity,
                    description = description,
                    lat = lat,
                    lng = lng,
                    openChatUrl = openChatUrl,
                )
            } else {
                RegisterPostCommand(
                    userId = userContext.getIdIfRequired(),
                    userName = userContext.getNameIfRequired(),
                    images = image,
                    title = title,
                    phoneNum = phoneNum,
                    time = time,
                    place = place,
                    gender = gender,
                    gratuity = gratuity,
                    description = description,
                    lat = lat,
                    lng = lng,
                    openChatUrl = openChatUrl,
                )
            }
        postService.registerPost(command)
        return SucceededApiResponseBody(data = null)
    }

    @PutMapping("/post")
    @Operation(
        summary = "게시글 수정 (이미지 제외)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun updatePost(
        @AuthenticationUser
        @Parameter(hidden = true)
        userContext: UserContext,
        @RequestBody
        request: UpdatePostRequest,
    ): SucceededApiResponseBody<Void> {
        postService.updatePost(command = request, userId = userContext.getIdIfRequired())
        return SucceededApiResponseBody(data = null)
    }

    @PostMapping("/post/image")
    @Operation(
        summary = "게시글에 이미지 추가",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun addPostImage(
        @AuthenticationUser
        @Parameter(hidden = true)
        userContext: UserContext,
        @RequestParam image: List<MultipartFile>,
        @RequestParam postId: UUID,
    ): SucceededApiResponseBody<Void> {
        postService.addPostImage(
            images = image,
            postId = postId,
            userId = userContext.getIdIfRequired(),
        )
        return SucceededApiResponseBody(data = null)
    }

    @DeleteMapping("/post/image")
    @Operation(
        summary = "이미지 삭제",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun deletePostImage(
        @AuthenticationUser
        @Parameter(hidden = true)
        userContext: UserContext,
        @RequestBody
        request: DeletePostImageRequest,
    ): SucceededApiResponseBody<Void> {
        postService.deletePostImage(
            userId = userContext.getIdIfRequired(),
            postId = request.postId,
            postImageId = request.postImageId,
        )
        return SucceededApiResponseBody(data = null)
    }

    @DeleteMapping("/post/{id}")
    @Operation(
        summary = "게시글 삭제",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun deletePost(
        @AuthenticationUser
        @Parameter(hidden = true)
        userContext: UserContext,
        @PathVariable id: UUID,
    ): SucceededApiResponseBody<Void> {
        postService.deletePost(postId = id, userId = userContext.getIdIfRequired())
        return SucceededApiResponseBody(data = null)
    }

    @GetMapping("/posts/mine")
    @Operation(
        summary = "내가 쓴 게시글",
        description = "현재는 게시글만 조회합니다",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun myPage(
        @AuthenticationUser
        @Parameter(hidden = true)
        userContext: UserContext,
    ): SucceededApiResponseBody<List<PostSummaryResponse>> {
        val response = postService.myPage(userId = userContext.getIdIfRequired())
        return SucceededApiResponseBody(data = response)
    }
}
