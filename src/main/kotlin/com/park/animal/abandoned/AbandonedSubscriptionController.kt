package com.park.animal.abandoned

import annotation.AuthenticationUser
import com.park.animal.abandoned.entity.AbandonedSubscription
import com.park.animal.common.config.SwaggerConfig
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
import java.time.LocalDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class AbandonedSubscriptionController(
    private val service: AbandonedSubscriptionService,
) {
    @PostMapping("/me/abandoned-subscriptions")
    @Operation(
        summary = "유기동물 지역 알림 구독",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun subscribe(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @RequestBody body: SubscribeRequest,
    ): SucceededApiResponseBody<SubscriptionResponse> {
        val saved = service.subscribe(passport.userId, body.uprCd, body.orgCd, body.animalType)
        return SucceededApiResponseBody(data = SubscriptionResponse.from(saved))
    }

    @DeleteMapping("/me/abandoned-subscriptions/{id}")
    @Operation(
        summary = "유기동물 지역 알림 구독 해제",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun unsubscribe(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
        @PathVariable id: UUID,
    ): SucceededApiResponseBody<Void> {
        service.unsubscribe(passport.userId, id)
        return SucceededApiResponseBody(data = null)
    }

    @GetMapping("/me/abandoned-subscriptions")
    @Operation(
        summary = "내 구독 목록",
        security = [SecurityRequirement(name = SwaggerConfig.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)],
    )
    fun list(
        @AuthenticationUser
        @Parameter(hidden = true)
        passport: Passport,
    ): SucceededApiResponseBody<List<SubscriptionResponse>> =
        SucceededApiResponseBody(data = service.list(passport.userId).map(SubscriptionResponse::from))

    data class SubscribeRequest(
        val uprCd: String,
        val orgCd: String? = null,
        val animalType: String? = null,
    )

    data class SubscriptionResponse(
        val id: UUID,
        val uprCd: String,
        val orgCd: String?,
        val animalType: String?,
        val createdAt: LocalDateTime,
    ) {
        companion object {
            fun from(s: AbandonedSubscription): SubscriptionResponse =
                SubscriptionResponse(
                    id = s.id,
                    uprCd = s.uprCd,
                    orgCd = s.orgCd,
                    animalType = s.animalType,
                    createdAt = s.createdAt,
                )
        }
    }
}
