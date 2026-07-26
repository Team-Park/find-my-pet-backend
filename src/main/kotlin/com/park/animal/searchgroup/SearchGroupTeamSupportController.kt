package com.park.animal.searchgroup

import annotation.AuthenticationUser
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.searchgroup.dto.CreateTeamSupportRequest
import com.park.animal.searchgroup.dto.SearchGroupTeamSupportResponse
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
class SearchGroupTeamSupportController(
    private val searchGroupTeamSupportService: SearchGroupTeamSupportService,
) {
    @PostMapping("/search-groups/{groupId}/team-supports")
    @Operation(
        summary = "팀 지원 연결 요청",
        description =
            "보호자가 요청하면 팀장 승인 대기, 팀장이 제안하면 보호자 승인 대기 상태가 된다. " +
                "요청자가 보호자이면서 그 팀의 팀장이면 바로 활성화된다. 초기 상태는 서버가 계산한다. " +
                "팀원(비팀장)이 호출하면 403 TEAM_LEADER_REQUIRED.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun request(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @RequestBody request: CreateTeamSupportRequest,
    ): SucceededApiResponseBody<SearchGroupTeamSupportResponse> =
        SucceededApiResponseBody(
            data =
                searchGroupTeamSupportService.request(
                    groupId = groupId,
                    teamId = request.teamId,
                    actorUserId = passport.userId,
                    message = request.message,
                ),
        )

    @GetMapping("/search-groups/{groupId}/team-supports")
    @Operation(
        summary = "팀 지원 연결 목록",
        description = "보호자는 전체, 팀장은 자기 팀의 연결만 조회한다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun list(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
    ): SucceededApiResponseBody<List<SearchGroupTeamSupportResponse>> =
        SucceededApiResponseBody(data = searchGroupTeamSupportService.list(groupId, passport.userId))

    @PostMapping("/search-groups/{groupId}/team-supports/{supportId}/accept")
    @Operation(
        summary = "팀 지원 수락",
        description = "상대편만 수락할 수 있다. 이미 활성이면 같은 결과를 그대로 돌려준다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun accept(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @PathVariable("supportId") supportId: UUID,
    ): SucceededApiResponseBody<SearchGroupTeamSupportResponse> =
        SucceededApiResponseBody(data = searchGroupTeamSupportService.accept(groupId, supportId, passport.userId))

    @PostMapping("/search-groups/{groupId}/team-supports/{supportId}/decline")
    @Operation(
        summary = "팀 지원 거절",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun decline(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @PathVariable("supportId") supportId: UUID,
    ): SucceededApiResponseBody<SearchGroupTeamSupportResponse> =
        SucceededApiResponseBody(data = searchGroupTeamSupportService.decline(groupId, supportId, passport.userId))

    @DeleteMapping("/search-groups/{groupId}/team-supports/{supportId}")
    @Operation(
        summary = "팀 지원 종료",
        description = "보호자가 실행하면 팀 제거, 팀장이 실행하면 우리 팀의 지원 종료다. 다른 팀에는 영향이 없다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun end(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @PathVariable("supportId") supportId: UUID,
    ): SucceededApiResponseBody<SearchGroupTeamSupportResponse> =
        SucceededApiResponseBody(data = searchGroupTeamSupportService.end(groupId, supportId, passport.userId))
}
