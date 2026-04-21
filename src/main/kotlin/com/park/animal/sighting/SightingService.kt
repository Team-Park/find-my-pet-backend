package com.park.animal.sighting

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.post.repository.PostRepository
import com.park.animal.sighting.dto.RegisterSightingRequest
import com.park.animal.sighting.dto.SightingResponse
import com.park.animal.sighting.entity.Sighting
import com.park.animal.sighting.repository.SightingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Service
class SightingService(
    private val sightingRepository: SightingRepository,
    private val postRepository: PostRepository,
) {
    @Transactional
    fun register(
        postId: UUID,
        reporterId: UUID,
        reporterName: String?,
        request: RegisterSightingRequest,
    ): SightingResponse {
        postRepository.findById(postId).getOrNull()
            ?: throw BusinessException(ErrorCode.NOT_FOUND_POST)
        val saved =
            sightingRepository.save(
                Sighting(
                    postId = postId,
                    reporterId = reporterId,
                    reporterName = reporterName,
                    lat = request.lat,
                    lng = request.lng,
                    sightedAt = request.sightedAt ?: LocalDateTime.now(),
                    note = request.note,
                    photoUrl = request.photoUrl,
                ),
            )
        return SightingResponse.from(saved, viewerId = reporterId)
    }

    @Transactional(readOnly = true)
    fun findByPost(
        postId: UUID,
        viewerId: UUID?,
    ): List<SightingResponse> =
        sightingRepository
            .findAllByPostIdAndDeletedAtIsNullOrderBySightedAtAsc(postId)
            .map { SightingResponse.from(it, viewerId) }

    @Transactional
    fun delete(
        sightingId: UUID,
        userId: UUID,
    ) {
        val sighting =
            sightingRepository.findById(sightingId).getOrNull()
                ?: throw BusinessException(ErrorCode.NOT_FOUND_SIGHTING)
        // 본인 제보만 삭제 가능 (게시글 작성자 예외 허용 여부는 추후 정책 결정)
        if (sighting.reporterId != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        sightingRepository.delete(sighting)
    }
}
