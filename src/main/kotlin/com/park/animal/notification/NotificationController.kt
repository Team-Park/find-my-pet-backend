package com.park.animal.notification

import annotation.AuthenticationUser
import com.park.animal.common.config.SwaggerConfig
import com.park.animal.notification.dto.NotificationResponse
import com.park.animal.notification.dto.UnreadCountResponse
import dto.Passport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class NotificationController(
    private val notificationService: NotificationService,
) {
    @GetMapping("/notifications")
    @Operation(
        summary = "내 알림 목록",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun list(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @RequestParam(name = "pageSize", required = false, defaultValue = "20") size: Int,
        @RequestParam(name = "pageOffset", required = false, defaultValue = "0") offset: Int,
    ): SucceededApiResponseBody<List<NotificationResponse>> =
        SucceededApiResponseBody(data = notificationService.list(passport.userId, size, offset))

    @GetMapping("/notifications/unread-count")
    @Operation(
        summary = "미읽음 알림 수",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun unreadCount(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
    ): SucceededApiResponseBody<UnreadCountResponse> =
        SucceededApiResponseBody(data = UnreadCountResponse(notificationService.unreadCount(passport.userId)))

    @PatchMapping("/notifications/{id}/read")
    @Operation(
        summary = "단일 알림 읽음 처리",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun markRead(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable id: UUID,
    ): SucceededApiResponseBody<Void> {
        notificationService.markRead(passport.userId, id)
        return SucceededApiResponseBody(data = null)
    }

    @PostMapping("/notifications/read-all")
    @Operation(
        summary = "모든 알림 읽음 처리",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun markAllRead(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
    ): SucceededApiResponseBody<Void> {
        notificationService.markAllRead(passport.userId)
        return SucceededApiResponseBody(data = null)
    }
}
