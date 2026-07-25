package com.park.animal.searchgroup

import annotation.AuthenticationUser
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.searchgroup.dto.JoinSearchGroupResponse
import com.park.animal.searchgroup.dto.SearchGroupMembershipResponse
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import dto.Passport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class SearchGroupMembershipController(
    private val membershipService: SearchGroupMembershipService,
) {
    @PostMapping("/search-groups/{groupId}/memberships")
    @Operation(
        summary = "함께 찾기 참여 또는 참여 신청",
        description = "참여 정책이 자유롭게 참여면 즉시 참여, 승인 후 참여면 신청 상태가 된다. 중복 호출은 멱등하다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun join(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
    ): SucceededApiResponseBody<JoinSearchGroupResponse> {
        // requireUserContext 를 직접 호출하면 컨텍스트가 없을 때 예외가 난다. 표시 이름은 없어도 진행한다.
        val userName = runCatching { passport.requireUserContext().userName.toString() }.getOrNull()
        return SucceededApiResponseBody(data = membershipService.join(groupId, passport.userId, userName))
    }

    @GetMapping("/search-groups/{groupId}/memberships")
    @Operation(
        summary = "수색그룹 참여자 목록",
        description = "보호자는 확인할 요청(대기)까지 보고, 참여자는 참여 중인 목록만 본다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun list(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @RequestParam(name = "status", required = false) status: SearchGroupMemberStatus?,
    ): SucceededApiResponseBody<List<SearchGroupMembershipResponse>> =
        SucceededApiResponseBody(data = membershipService.list(groupId, passport.userId, status))

    @PostMapping("/search-groups/{groupId}/memberships/{membershipId}/approve")
    @Operation(
        summary = "참여 요청 승인 (보호자)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun approve(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @PathVariable("membershipId") membershipId: UUID,
    ): SucceededApiResponseBody<SearchGroupMembershipResponse> =
        SucceededApiResponseBody(data = membershipService.approve(groupId, membershipId, passport.userId))

    @PostMapping("/search-groups/{groupId}/memberships/{membershipId}/reject")
    @Operation(
        summary = "참여 요청 거절 (보호자)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun reject(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @PathVariable("membershipId") membershipId: UUID,
    ): SucceededApiResponseBody<SearchGroupMembershipResponse> =
        SucceededApiResponseBody(data = membershipService.reject(groupId, membershipId, passport.userId))

    @PostMapping("/search-groups/{groupId}/memberships/{membershipId}/remove")
    @Operation(
        summary = "참여자 내보내기 (보호자)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun remove(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @PathVariable("membershipId") membershipId: UUID,
    ): SucceededApiResponseBody<SearchGroupMembershipResponse> =
        SucceededApiResponseBody(data = membershipService.remove(groupId, membershipId, passport.userId))

    @DeleteMapping("/search-groups/{groupId}/memberships/me")
    @Operation(
        summary = "개인 참여 종료",
        description = "직접 참여한 사용자만 사용할 수 있다. 팀을 통해 권한을 얻은 사용자는 팀 지원 종료로만 권한이 사라진다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun leaveMe(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
    ): SucceededApiResponseBody<SearchGroupMembershipResponse> =
        SucceededApiResponseBody(data = membershipService.leaveMe(groupId, passport.userId))
}
