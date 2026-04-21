package com.park.animal.matching

import annotation.PublicEndPoint
import com.park.animal.matching.dto.MatchCandidate
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class AiMatchingController(
    private val aiMatchingService: AiMatchingService,
) {
    @PublicEndPoint
    @GetMapping("/post/{id}/similar-candidates")
    @Operation(
        summary = "실종 게시글의 닮은 구조동물 후보 목록",
        description =
            "AI 사진 유사도 기반 후보. 최종 판단은 보호자가 직접 확인해야 하며, 본 응답은 '닮은 아이들이 있어요' 톤의 참고용. " +
                "spring-ai 이미지 확장 전까지 빈 리스트 반환.",
    )
    suspend fun getSimilarCandidates(
        @PathVariable("id") postId: UUID,
        @RequestParam("limit", required = false, defaultValue = "5") limit: Int,
    ): SucceededApiResponseBody<List<MatchCandidate>> {
        val candidates = aiMatchingService.findCandidates(postId, limit)
        return SucceededApiResponseBody(data = candidates)
    }
}
