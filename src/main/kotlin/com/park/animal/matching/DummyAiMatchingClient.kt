package com.park.animal.matching

import com.park.animal.matching.dto.MatchCandidate
import com.park.animal.post.entity.Post
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 실제 spring-ai 이미지 확장이 완료되기 전까지 사용하는 더미 구현.
 *
 * 빈 후보 리스트를 반환하여 UI가 "오늘 닮은 아이가 없어요" 상태를 자연스럽게 보여주도록 함.
 *
 * 활성 조건: `fmp.matching.enabled=false` (default) — spring-ai 준비 후 `true` 로 전환 예정.
 */
@Component
@ConditionalOnProperty(
    prefix = "fmp.matching",
    name = ["enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class DummyAiMatchingClient : AiMatchingClient {
    override suspend fun findSimilarCandidates(
        post: Post,
        candidateLimit: Int,
    ): List<MatchCandidate> = emptyList()
}
