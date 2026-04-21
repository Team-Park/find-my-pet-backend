package com.park.animal.matching

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.matching.dto.MatchCandidate
import com.park.animal.post.repository.PostRepository
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Service
class AiMatchingService(
    private val aiMatchingClient: AiMatchingClient,
    private val postRepository: PostRepository,
) {
    suspend fun findCandidates(
        postId: UUID,
        limit: Int,
    ): List<MatchCandidate> {
        val post = postRepository.findById(postId).getOrNull() ?: throw BusinessException(ErrorCode.NOT_FOUND_POST)
        return aiMatchingClient.findSimilarCandidates(post, limit)
    }
}
