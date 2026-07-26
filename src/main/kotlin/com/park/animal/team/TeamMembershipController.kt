package com.park.animal.team

import annotation.AuthenticationUser
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.team.dto.TeamMembershipResponse
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
class TeamMembershipController(
    private val teamMembershipService: TeamMembershipService,
) {
    @PostMapping("/teams/{teamId}/memberships")
    @Operation(
        summary = "팀 참여 요청",
        description = "팀장 승인 후 팀원이 된다. 이미 팀원이거나 대기 중이면 같은 멤버십을 그대로 돌려준다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun request(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
    ): SucceededApiResponseBody<TeamMembershipResponse> {
        val userName = runCatching { passport.requireUserContext().userName.toString() }.getOrNull()
        return SucceededApiResponseBody(data = teamMembershipService.request(teamId, passport.userId, userName))
    }

    @GetMapping("/teams/{teamId}/memberships")
    @Operation(
        summary = "팀원 목록",
        description = "팀장은 승인 대기까지, 그 외에는 활성 팀원만 조회한다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun list(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
    ): SucceededApiResponseBody<List<TeamMembershipResponse>> =
        SucceededApiResponseBody(data = teamMembershipService.list(teamId, passport.userId))

    @PostMapping("/teams/{teamId}/memberships/{membershipId}/approve")
    @Operation(
        summary = "팀원 승인 (팀장)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun approve(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
        @PathVariable("membershipId") membershipId: UUID,
    ): SucceededApiResponseBody<TeamMembershipResponse> =
        SucceededApiResponseBody(data = teamMembershipService.approve(teamId, membershipId, passport.userId))

    @PostMapping("/teams/{teamId}/memberships/{membershipId}/reject")
    @Operation(
        summary = "팀원 거절 (팀장)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun reject(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
        @PathVariable("membershipId") membershipId: UUID,
    ): SucceededApiResponseBody<TeamMembershipResponse> =
        SucceededApiResponseBody(data = teamMembershipService.reject(teamId, membershipId, passport.userId))

    @PostMapping("/teams/{teamId}/memberships/{membershipId}/remove")
    @Operation(
        summary = "팀원 내보내기 (팀장)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun remove(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
        @PathVariable("membershipId") membershipId: UUID,
    ): SucceededApiResponseBody<TeamMembershipResponse> =
        SucceededApiResponseBody(data = teamMembershipService.remove(teamId, membershipId, passport.userId))

    @DeleteMapping("/teams/{teamId}/memberships/me")
    @Operation(
        summary = "팀 나가기",
        description = "활성 팀의 팀장은 팀장 권한을 넘기거나 팀을 보관한 뒤에 나갈 수 있다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun leaveMe(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
    ): SucceededApiResponseBody<TeamMembershipResponse> =
        SucceededApiResponseBody(data = teamMembershipService.leaveMe(teamId, passport.userId))
}
