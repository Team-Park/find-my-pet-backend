package com.park.animal.post

import annotation.AuthenticationUser
import annotation.PublicEndPoint
import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.common.constants.OrderBy
import com.park.animal.post.dto.DeletePostImageRequest
import com.park.animal.post.dto.PostDetailResponse
import com.park.animal.post.dto.PostNearbyResponse
import com.park.animal.post.dto.PostSummaryResponse
import com.park.animal.post.dto.RegisterPostCommand
import com.park.animal.post.dto.SummarizedPostsByPageQuery
import com.park.animal.post.dto.UpdatePostRequest
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.entity.JoinPolicy
import dto.Passport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import model.Role
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
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
    suspend fun getPosts(
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

    @GetMapping("/posts/nearby")
    @PublicEndPoint
    @Operation(
        summary = "반경 내 게시글 조회",
        description = "중심 좌표(lat, lng)와 radiusKm 반경 안의 실종 게시글을 가까운 순으로 페이지네이션 조회 (Haversine).",
    )
    suspend fun getPostsNearby(
        @RequestParam("lat") lat: Double,
        @RequestParam("lng") lng: Double,
        @RequestParam("radiusKm", required = false, defaultValue = "5") radiusKm: Double,
        @RequestParam("pageSize", required = false, defaultValue = "20") size: Long,
        @RequestParam("pageOffset", required = false, defaultValue = "0") offset: Long,
    ): PaginatedApiResponseBody<PostNearbyResponse> {
        val page = postService.findNearbyPosts(lat, lng, radiusKm, size, offset)
        return PaginatedApiResponseBody(
            data =
                PaginatedApiResponseDto(
                    contents = page.contents,
                    hasNextPage = page.hasNextPage,
                    totalCount = page.totalCount,
                ),
        )
    }

    @GetMapping("/post/{id}")
    @PublicEndPoint
    suspend fun getPost(
        @PathVariable id: UUID,
        @AuthenticationUser(isRequired = false)
        @Parameter(hidden = true)
        passport: Passport?,
    ): SucceededApiResponseBody<PostDetailResponse> {
        val response = postService.findDetailPost(id, passport?.userId)
        return SucceededApiResponseBody(data = response)
    }

    @PostMapping(path = ["/post"], consumes = ["multipart/form-data", "application/json"])
    @Operation(
        summary = "게시글 등록 API",
        description =
            "실종 소식 등록. missingAnimalStatus 가 SEARCHING 이면 수색그룹이 함께 하나 생성된다.\n" +
                "joinPolicy 는 직접 참여 정책이며 기본값은 OPEN(자유롭게 참여), 다른 값은 " +
                "APPROVAL_REQUIRED(승인 후 참여) 뿐이다.\n" +
                "주의: enum 파라미터 변환은 대소문자를 구분한다(대문자만 허용). 'open' 같은 오타는 " +
                "MethodArgumentTypeMismatchException → 400 MISSING_PARAMETER 로 응답한다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    suspend fun registerPost(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
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
        @RequestParam missingAnimalStatus: MissingAnimalStatus,
        @RequestParam(required = false, defaultValue = "DOG") animalType: AnimalType,
        @RequestParam(required = false) breedId: UUID?,
        @RequestParam(required = false, defaultValue = "OPEN") joinPolicy: JoinPolicy,
    ): SucceededApiResponseBody<Void> {
        val resolvedUserName =
            if (customNickname != null && passport.role == Role.ROLE_ADMIN) {
                customNickname
            } else {
                passport.requireUserContext().userName.toString()
            }
        val command =
            RegisterPostCommand(
                userId = passport.userId,
                userName = resolvedUserName,
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
                missingAnimalStatus = missingAnimalStatus,
                animalType = animalType,
                breedId = breedId,
                applicationId = passport.signInApplicationId,
                joinPolicy = joinPolicy,
            )
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
        passport: Passport,
        @RequestBody
        request: UpdatePostRequest,
    ): SucceededApiResponseBody<Void> {
        postService.updatePost(command = request, userId = passport.userId)
        return SucceededApiResponseBody(data = null)
    }

    @PostMapping("/post/image", consumes = ["multipart/form-data", "application/json"])
    @Operation(
        summary = "게시글에 이미지 추가",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun addPostImage(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @RequestParam image: List<MultipartFile>,
        @RequestParam postId: UUID,
    ): SucceededApiResponseBody<Void> {
        postService.addPostImage(
            images = image,
            postId = postId,
            userId = passport.userId,
            applicationId = passport.signInApplicationId,
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
        passport: Passport,
        @RequestBody
        request: DeletePostImageRequest,
    ): SucceededApiResponseBody<Void> {
        postService.deletePostImage(
            userId = passport.userId,
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
        passport: Passport,
        @PathVariable id: UUID,
    ): SucceededApiResponseBody<Void> {
        postService.deletePost(postId = id, userId = passport.userId)
        return SucceededApiResponseBody(data = null)
    }

    @GetMapping("/posts/mine")
    @Operation(
        summary = "내가 쓴 게시글",
        description = "현재는 게시글만 조회합니다",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    suspend fun myPage(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
    ): SucceededApiResponseBody<List<PostSummaryResponse>> {
        val response = postService.myPage(userId = passport.userId)
        return SucceededApiResponseBody(data = response)
    }

    @PatchMapping("/post/renewal-status")
    @Operation(
        summary = "게시글 상태 변경",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun updateMissingAnimalStatus(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @RequestParam("missingAnimalStatus")
        missingAnimalStatus: MissingAnimalStatus,
        @RequestParam("postId")
        postId: UUID,
    ): SucceededApiResponseBody<Unit> {
        postService.updateStatus(
            userId = passport.userId,
            postId = postId,
            status = missingAnimalStatus,
        )
        return SucceededApiResponseBody.succeed()
    }
}
