package com.park.animal.post

import annotation.AuthenticationUser
import annotation.PublicEndPoint
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.common.constants.OrderBy
import com.park.animal.post.dto.RegisterReviewRequest
import com.park.animal.post.dto.ReviewDetailResponseDto
import com.park.animal.post.dto.SummarizedPostsByPageQuery
import com.park.animal.post.dto.SummaryReviewResponse
import dto.UserContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.woo.http.PaginatedApiResponseBody
import org.woo.http.PaginatedApiResponseDto
import org.woo.http.SucceededApiResponseBody

@RestController
@RequestMapping("/api/v1")
class ReviewController(
    val reviewService: ReviewService,
) {
    @PostMapping("/review")
    @Operation(
        summary = "후기 등록",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun registerReview(
        @AuthenticationUser
        @Parameter(hidden = true)
        userContext: UserContext,
        @RequestBody
        request: RegisterReviewRequest,
    ): SucceededApiResponseBody<Unit> {
        reviewService.registerReview(
            userId = userContext.getIdIfRequired().toString(),
            authorName = userContext.getNameIfRequired(),
            title = request.title,
            content = request.content,
        )
        return SucceededApiResponseBody.succeed()
    }

    @GetMapping("/reviews")
    @PublicEndPoint
    @Operation(
        summary = "후기 페이지 네이션",
        description = "totalCount 는 페이지를 뜻한다.",
    )
    fun getReviews(
        @RequestParam(name = "pageSize", required = false, defaultValue = "20") size: Long,
        @RequestParam(name = "pageOffset", required = false, defaultValue = "0") offset: Long,
        @RequestParam(name = "orderBy", required = false, defaultValue = "CREATED_AT_DESC") orderBy: OrderBy,
    ): PaginatedApiResponseBody<SummaryReviewResponse> {
        val response = reviewService.findReviewList(SummarizedPostsByPageQuery(size, offset, orderBy))
        return PaginatedApiResponseBody(
            data =
                PaginatedApiResponseDto(
                    contents = response.content,
                    hasNextPage = response.hasNext(),
                    totalCount = response.totalPages.toLong(),
                ),
        )
    }

    @GetMapping("/review/{id}")
    @PublicEndPoint
    @Operation(
        summary = "후기 상세 조회",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun getReview(
        @AuthenticationUser(isRequired = false)
        @Parameter(hidden = true)
        userContext: UserContext?,
        @PathVariable("id") id: String,
    ): SucceededApiResponseBody<ReviewDetailResponseDto> {
        val response = reviewService.getDetail(id, userContext?.userId)
        return SucceededApiResponseBody(data = response)
    }
}
