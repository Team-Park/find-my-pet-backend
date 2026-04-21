package com.park.animal.flyer

import annotation.AuthenticationUser
import annotation.PublicEndPoint
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.flyer.dto.FlyerLocationResponse
import com.park.animal.flyer.dto.RegisterFlyerRequest
import com.park.animal.flyer.entity.FlyerStatus
import com.park.animal.flyer.entity.FlyerVisibility
import dto.Passport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class FlyerController(
    private val flyerService: FlyerService,
) {
    @PostMapping("/post/{postId}/flyer")
    @Operation(
        summary = "전단지 좌표 등록",
        description = "게시글 작성자만 자신의 실종 게시글에 전단지 위치를 기록할 수 있음.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun register(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("postId") postId: UUID,
        @RequestBody request: RegisterFlyerRequest,
    ): SucceededApiResponseBody<FlyerLocationResponse> {
        val created = flyerService.register(postId, passport.userId, request)
        return SucceededApiResponseBody(data = created)
    }

    @GetMapping("/post/{postId}/flyers")
    @Operation(
        summary = "내 전단지 목록 (본인만)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun getMyFlyers(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("postId") postId: UUID,
    ): SucceededApiResponseBody<List<FlyerLocationResponse>> {
        val list = flyerService.findMine(postId, passport.userId)
        return SucceededApiResponseBody(data = list)
    }

    @PublicEndPoint
    @GetMapping("/post/{postId}/flyers/public")
    @Operation(
        summary = "공개된 전단지 목록",
        description = "PUBLIC visibility 로 설정된 전단지만 반환. 누구나 조회 가능.",
    )
    fun getPublicFlyers(
        @PathVariable("postId") postId: UUID,
    ): SucceededApiResponseBody<List<FlyerLocationResponse>> {
        val list = flyerService.findPublic(postId)
        return SucceededApiResponseBody(data = list)
    }

    @PatchMapping("/flyer/{flyerId}/status")
    @Operation(
        summary = "전단지 회수 상태 토글 (POSTED ↔ COLLECTED)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun updateStatus(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("flyerId") flyerId: UUID,
        @RequestParam("status") status: FlyerStatus,
    ): SucceededApiResponseBody<FlyerLocationResponse> {
        val updated = flyerService.updateStatus(flyerId, passport.userId, status)
        return SucceededApiResponseBody(data = updated)
    }

    @PatchMapping("/flyer/{flyerId}/visibility")
    @Operation(
        summary = "전단지 공개/비공개 전환",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun updateVisibility(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("flyerId") flyerId: UUID,
        @RequestParam("visibility") visibility: FlyerVisibility,
    ): SucceededApiResponseBody<FlyerLocationResponse> {
        val updated = flyerService.updateVisibility(flyerId, passport.userId, visibility)
        return SucceededApiResponseBody(data = updated)
    }

    @DeleteMapping("/flyer/{flyerId}")
    @Operation(
        summary = "전단지 삭제 (soft delete)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun delete(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("flyerId") flyerId: UUID,
    ): SucceededApiResponseBody<Unit> {
        flyerService.delete(flyerId, passport.userId)
        return SucceededApiResponseBody.succeed()
    }
}
