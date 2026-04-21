package com.park.animal.reachable

import annotation.PublicEndPoint
import com.park.animal.reachable.dto.ReachableResponse
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class ReachableController(
    private val reachableService: ReachableService,
) {
    @PublicEndPoint
    @GetMapping("/post/{id}/reachable")
    @Operation(
        summary = "실종 게시글 도달 영역 (ORS isochrone 또는 circle fallback)",
        description =
            "DOG: OpenRouteService isochrone (foot-walking, 실제 도로망 기반). 실패/쿼터소진 시 원으로 fallback.\n" +
                "CAT: 시간 무관 고정 반경(150/500/1500m, 7일+ 확장 최대 3km).\n" +
                "OTHER: 완전 고정 (30/100/300m).",
    )
    suspend fun getReachable(
        @PathVariable("id") postId: UUID,
    ): SucceededApiResponseBody<ReachableResponse> {
        val response = reachableService.computeReachable(postId)
        return SucceededApiResponseBody(data = response)
    }
}
