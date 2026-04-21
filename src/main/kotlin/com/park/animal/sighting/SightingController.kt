package com.park.animal.sighting

import annotation.AuthenticationUser
import annotation.PublicEndPoint
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.sighting.dto.RegisterSightingRequest
import com.park.animal.sighting.dto.SightingResponse
import dto.Passport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class SightingController(
    private val sightingService: SightingService,
) {
    @PostMapping("/post/{postId}/sighting")
    @Operation(
        summary = "목격 제보 등록",
        description = "로그인 사용자 누구나 해당 실종 게시글에 '여기서 봤어요' 제보 가능.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun register(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("postId") postId: UUID,
        @RequestBody request: RegisterSightingRequest,
    ): SucceededApiResponseBody<SightingResponse> {
        val userName = runCatching { passport.requireUserContext().userName.toString() }.getOrNull()
        val created = sightingService.register(postId, passport.userId, userName, request)
        return SucceededApiResponseBody(data = created)
    }

    @PublicEndPoint
    @GetMapping("/post/{postId}/sightings")
    @Operation(
        summary = "게시글 목격 제보 목록 (공개)",
        description = "시간순 정렬. 로그인 사용자면 isMine 플래그로 본인 제보 표시.",
    )
    fun getByPost(
        @PathVariable("postId") postId: UUID,
        @AuthenticationUser(isRequired = false)
        @Parameter(hidden = true)
        passport: Passport?,
    ): SucceededApiResponseBody<List<SightingResponse>> {
        val list = sightingService.findByPost(postId, passport?.userId)
        return SucceededApiResponseBody(data = list)
    }

    @DeleteMapping("/sighting/{sightingId}")
    @Operation(
        summary = "목격 제보 삭제 (제보자 본인)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun delete(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("sightingId") sightingId: UUID,
    ): SucceededApiResponseBody<Unit> {
        sightingService.delete(sightingId, passport.userId)
        return SucceededApiResponseBody.succeed()
    }
}
