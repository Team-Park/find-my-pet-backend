package com.park.animal.bookmark

import annotation.AuthenticationUser
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.post.dto.PostSummaryResponse
import dto.Passport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class BookmarkController(
    private val bookmarkService: BookmarkService,
) {
    @PostMapping("/post/{postId}/bookmark")
    @Operation(
        summary = "게시글 즐겨찾기 추가",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun add(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable postId: UUID,
    ): SucceededApiResponseBody<Void> {
        bookmarkService.add(passport.userId, postId)
        return SucceededApiResponseBody(data = null)
    }

    @DeleteMapping("/post/{postId}/bookmark")
    @Operation(
        summary = "게시글 즐겨찾기 해제",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun remove(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable postId: UUID,
    ): SucceededApiResponseBody<Void> {
        bookmarkService.remove(passport.userId, postId)
        return SucceededApiResponseBody(data = null)
    }

    @GetMapping("/me/bookmarks")
    @Operation(
        summary = "내 즐겨찾기 게시글 목록",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun listMine(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
    ): SucceededApiResponseBody<List<PostSummaryResponse>> =
        SucceededApiResponseBody(data = bookmarkService.listMyBookmarkedPosts(passport.userId))
}
