package com.park.animal.searchgroup

import annotation.AuthenticationUser
import annotation.PublicEndPoint
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.searchgroup.dto.SearchGroupCtaResponse
import com.park.animal.searchgroup.dto.SearchGroupDetailResponse
import com.park.animal.searchgroup.dto.SearchGroupEventResponse
import com.park.animal.searchgroup.dto.UpdateJoinPolicyRequest
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
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

/**
 * 수색그룹 공개 CTA · 상세 · 수색 종료 · 활동 기록 (계약 §8 #2·#3·#5·#6).
 *
 * 계약 §1-16 에 따라 모든 핸들러에 `@PublicEndPoint` 또는 `@AuthenticationUser` 중 하나가 반드시 있다.
 * 없으면 `PassportInterceptor` 가 403 을 낸다.
 *
 * `passport.requireUserContext()` 를 직접 호출하지 않는다(계약 §1-11) — 이 컨트롤러의 모든
 * 응답은 표시 이름을 필요로 하지 않는다.
 *
 * **Task 6 이 이 클래스에 덧붙인다**: 생성자에 `SearchGroupMembershipService` 파라미터 한 줄,
 * 그리고 `PATCH /search-groups/{groupId}/join-policy`(계약 §8 #4) 핸들러 하나.
 * 전체 교체가 아니라 추가다.
 */
@RestController
@RequestMapping("/api/v1")
class SearchGroupController(
    private val searchGroupService: SearchGroupService,
) {
    @PublicEndPoint
    @GetMapping("/posts/{postId}/search-group")
    @Operation(
        summary = "공개 실종 소식의 함께 찾기 카드",
        description =
            "비로그인 접근 가능. 카카오톡 공유 링크 방문자가 보는 CTA 다.\n" +
                "viewerAction: JOIN_NOW(즉시 참여) / REQUEST_JOIN(승인 후 참여) / ALREADY_JOINED / " +
                "LOGIN_REQUIRED(비로그인) / UNAVAILABLE(종료·목격 소식·권한 없음·차단).\n" +
                "이 백엔드는 401 을 내지 않으므로 비로그인은 상태코드가 아니라 LOGIN_REQUIRED 로 판별한다.\n" +
                "그룹이 없는 글과 삭제된 글은 모두 404 NOT_FOUND_SEARCH_GROUP.",
    )
    fun getCta(
        @PathVariable("postId") postId: UUID,
        @AuthenticationUser(isRequired = false)
        @Parameter(hidden = true)
        passport: Passport?,
    ): SucceededApiResponseBody<SearchGroupCtaResponse> =
        SucceededApiResponseBody(data = searchGroupService.getCta(postId, passport?.userId))

    @GetMapping("/search-groups/{groupId}")
    @Operation(
        summary = "수색그룹 상세",
        description =
            "유효 참여자(보호자·직접 ACTIVE 참여자·ACTIVE 팀 지원의 ACTIVE 팀원)만 조회 가능.\n" +
                "비참여자·차단자는 동일하게 403 SEARCH_GROUP_ACCESS_DENIED, 삭제된 실종 소식은 404.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun getDetail(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
    ): SucceededApiResponseBody<SearchGroupDetailResponse> =
        SucceededApiResponseBody(data = searchGroupService.getDetail(groupId, passport.userId))

    @PostMapping("/search-groups/{groupId}/end")
    @Operation(
        summary = "수색 종료 (보호자 전용)",
        description =
            "실종 소식을 FOUND 로, 수색그룹을 ARCHIVED(FOUND) 로 전환하고 유효 참여자에게 " +
                "SEARCH_ENDED 알림을 1인당 1건 보낸다.\n" +
                "재활성화 API 는 없다. 이미 종료됐으면 410 SEARCH_ALREADY_ENDED, 보호자가 아니면 403.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun endSearch(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
    ): SucceededApiResponseBody<SearchGroupDetailResponse> =
        SucceededApiResponseBody(data = searchGroupService.endSearch(groupId, passport.userId))

    @GetMapping("/search-groups/{groupId}/events")
    @Operation(
        summary = "수색그룹 활동 기록",
        description =
            "createdAt DESC, id DESC 정렬. 유효 참여자만 조회 가능.\n" +
                "pageSize 는 서버가 최대 50 으로 제한한다(설계 §16.7).",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun listEvents(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @RequestParam(name = "pageSize", required = false, defaultValue = "20") size: Int,
        @RequestParam(name = "pageOffset", required = false, defaultValue = "0") offset: Int,
    ): SucceededApiResponseBody<List<SearchGroupEventResponse>> =
        SucceededApiResponseBody(
            data = searchGroupService.listEvents(groupId, passport.userId, size, offset),
        )

    @PatchMapping("/search-groups/{groupId}/join-policy")
    @Operation(
        summary = "참여 정책 변경 (보호자)",
        description = "기존 참여자는 그대로 유지되고, 대기 중인 요청도 자동 승인되지 않는다.",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun updateJoinPolicy(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable("groupId") groupId: UUID,
        @RequestBody request: UpdateJoinPolicyRequest,
    ): SucceededApiResponseBody<SearchGroupDetailResponse> =
        SucceededApiResponseBody(
            data = searchGroupService.updateJoinPolicy(groupId, passport.userId, request.joinPolicy),
        )
}
