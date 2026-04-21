package com.park.animal.matching

import com.park.animal.matching.dto.MatchCandidate
import com.park.animal.post.entity.Post

/**
 * 플랫폼 spring-ai 에 이미지 유사도 매칭을 위임하는 인터페이스.
 *
 * 현재 spring-ai 는 이미지/멀티모달 미지원 → [DummyAiMatchingClient] 가 빈 리스트를 반환한다.
 * spring-ai 이미지 지원 확장 (proto/DTO/Gemini Vision wiring) 완료 후 실제 구현체
 * `GeminiVisionMatchingClient` 로 Bean 교체.
 */
interface AiMatchingClient {
    /**
     * 주어진 실종 게시글에 "닮은" 구조동물 후보를 돌려준다.
     *
     * @param post 실종 게시글 엔티티 (이미지 URL 포함)
     * @param candidateLimit 반환할 최대 후보 수 (상위 N개)
     * @return 닮은 순으로 정렬된 후보 리스트. 빈 리스트 허용.
     */
    suspend fun findSimilarCandidates(
        post: Post,
        candidateLimit: Int = 5,
    ): List<MatchCandidate>
}
