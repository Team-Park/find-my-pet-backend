package com.park.animal.searchgroup

import annotation.AuthenticationUser
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.searchgroup.dto.BlockSearchGroupUserRequest
import com.park.animal.searchgroup.dto.SearchGroupBlockResponse
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
class SearchGroupBlockController(
    private val blockService: SearchGroupBlockService,
) {
    @GetMapping("/search-groups/{groupId}/blocks")
    @Operation(
        summary = "차단 목록 (보호자 전용)",
        description = "보호자만 조회할 수 있다. 참여자에게는 어떤 경로로도 노출하지 않는다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun list(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
    ): SucceededApiResponseBody<List<SearchGroupBlockResponse>> =
        SucceededApiResponseBody(data = blockService.listBlocks(groupId, passport.userId))

    @PostMapping("/search-groups/{groupId}/blocks")
    @Operation(
        summary = "사용자 차단 (보호자)",
        description = "직접 참여 중이면 참여도 함께 종료되고, 팀을 통해 얻은 권한도 즉시 사라진다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun block(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @RequestBody request: BlockSearchGroupUserRequest,
    ): SucceededApiResponseBody<SearchGroupBlockResponse> =
        SucceededApiResponseBody(
            data = blockService.block(groupId, request.targetUserId, passport.userId, request.reason),
        )

    @DeleteMapping("/search-groups/{groupId}/blocks/{userId}")
    @Operation(
        summary = "차단 해제 (보호자)",
        description = "해제하면 현재 참여 정책에 따라 다시 참여하거나 신청할 수 있다. 이미 해제된 상태로 다시 호출해도 성공이다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun unblock(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @PathVariable("userId") userId: UUID,
    ): SucceededApiResponseBody<Unit> {
        blockService.unblock(groupId, userId, passport.userId)
        return SucceededApiResponseBody.succeed()
    }
}
