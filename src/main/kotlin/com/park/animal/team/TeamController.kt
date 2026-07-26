package com.park.animal.team

import annotation.AuthenticationUser
import annotation.PublicEndPoint
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.team.dto.CreateTeamRequest
import com.park.animal.team.dto.TeamResponse
import com.park.animal.team.dto.TeamSummaryResponse
import com.park.animal.team.dto.TransferTeamLeadershipRequest
import com.park.animal.team.dto.UpdateTeamRequest
import dto.Passport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.woo.http.PaginatedApiResponseBody
import org.woo.http.PaginatedApiResponseDto
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class TeamController(
    private val teamService: TeamService,
) {
    @PostMapping("/teams")
    @Operation(
        summary = "팀 생성",
        description = "생성자가 팀장이 된다. 이름 2~30자, 소개 200자 이하.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun create(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @RequestBody request: CreateTeamRequest,
    ): SucceededApiResponseBody<TeamResponse> {
        val userName = runCatching { passport.requireUserContext().userName.toString() }.getOrNull()
        val created = teamService.create(passport.userId, userName, request.name, request.description)
        return SucceededApiResponseBody(data = created)
    }

    @PublicEndPoint
    @GetMapping("/teams")
    @Operation(
        summary = "팀 목록 (공개)",
        description =
            "활성 팀만 이름순으로 조회한다. q 는 팀 이름 부분 일치. " +
                "로그인 상태면 각 항목의 viewerRole / viewerStatus 가 채워지고 비로그인이면 null 이다.",
    )
    fun list(
        @RequestParam(name = "q", required = false) q: String?,
        @RequestParam(name = "pageSize", required = false, defaultValue = "20") size: Long,
        @RequestParam(name = "pageOffset", required = false, defaultValue = "0") offset: Long,
        @AuthenticationUser(isRequired = false)
        @Parameter(hidden = true)
        passport: Passport?,
    ): PaginatedApiResponseBody<TeamSummaryResponse> {
        val page = teamService.list(q, size, offset, passport?.userId)
        return PaginatedApiResponseBody(
            data =
                PaginatedApiResponseDto(
                    contents = page.contents,
                    hasNextPage = page.hasNextPage,
                    totalCount = page.totalCount,
                ),
        )
    }

    @PublicEndPoint
    @GetMapping("/teams/{teamId}")
    @Operation(
        summary = "팀 상세 (공개)",
        description = "로그인 사용자면 viewerRole/viewerStatus 로 본인의 역할을 함께 내려준다. 보관된 팀은 404.",
    )
    fun detail(
        @PathVariable("teamId") teamId: UUID,
        @AuthenticationUser(isRequired = false)
        @Parameter(hidden = true)
        passport: Passport?,
    ): SucceededApiResponseBody<TeamResponse> = SucceededApiResponseBody(data = teamService.detail(teamId, passport?.userId))

    @PatchMapping("/teams/{teamId}")
    @Operation(
        summary = "팀 정보 수정 (팀장)",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun update(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
        @RequestBody request: UpdateTeamRequest,
    ): SucceededApiResponseBody<TeamResponse> =
        SucceededApiResponseBody(
            data = teamService.update(teamId, passport.userId, request.name, request.description),
        )

    @PostMapping("/teams/{teamId}/transfer-leadership")
    @Operation(
        summary = "팀장 권한 이전 (팀장)",
        description = "대상은 활성 팀원이어야 한다. 이전 후 활성 팀장은 계속 한 명이다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun transferLeadership(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
        @RequestBody request: TransferTeamLeadershipRequest,
    ): SucceededApiResponseBody<TeamResponse> =
        SucceededApiResponseBody(
            data = teamService.transferLeadership(teamId, passport.userId, request.targetMembershipId),
        )

    /** 계약 §8 #33 — 설계 §6.4 근거, §18 표에는 없던 추가. */
    @PostMapping("/teams/{teamId}/archive")
    @Operation(
        summary = "팀 보관 (팀장)",
        description =
            "팀 활동을 종료한다. 지원 중이던 모든 수색그룹 연결이 함께 종료되어 팀원의 파생 권한이 사라지고, " +
                "보관 후에는 팀장도 팀을 나갈 수 있다. 이미 보관된 팀에 다시 호출해도 같은 결과를 돌려준다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun archive(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("teamId") teamId: UUID,
    ): SucceededApiResponseBody<TeamResponse> = SucceededApiResponseBody(data = teamService.archive(teamId, passport.userId))
}
