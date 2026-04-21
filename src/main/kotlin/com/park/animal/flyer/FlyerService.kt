package com.park.animal.flyer

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.flyer.dto.FlyerLocationResponse
import com.park.animal.flyer.dto.RegisterFlyerRequest
import com.park.animal.flyer.entity.FlyerLocation
import com.park.animal.flyer.entity.FlyerStatus
import com.park.animal.flyer.entity.FlyerVisibility
import com.park.animal.flyer.repository.FlyerLocationRepository
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Service
class FlyerService(
    private val flyerRepository: FlyerLocationRepository,
    private val postRepository: PostRepository,
) {
    @Transactional
    fun register(
        postId: UUID,
        userId: UUID,
        request: RegisterFlyerRequest,
    ): FlyerLocationResponse {
        val post = getPost(postId)
        requireOwner(post, userId)
        val saved =
            flyerRepository.save(
                FlyerLocation(
                    postId = postId,
                    postedBy = userId,
                    lat = request.lat,
                    lng = request.lng,
                    note = request.note,
                    visibility = request.visibility,
                ),
            )
        return FlyerLocationResponse.from(saved)
    }

    @Transactional(readOnly = true)
    fun findMine(
        postId: UUID,
        userId: UUID,
    ): List<FlyerLocationResponse> {
        val post = getPost(postId)
        requireOwner(post, userId)
        return flyerRepository
            .findAllByPostIdAndDeletedAtIsNullOrderByPostedAtAsc(postId)
            .map(FlyerLocationResponse::from)
    }

    /** 공개 조회 — 게시글 누구나 조회. PUBLIC visibility 만 반환. */
    @Transactional(readOnly = true)
    fun findPublic(postId: UUID): List<FlyerLocationResponse> =
        flyerRepository
            .findAllByPostIdAndVisibilityAndDeletedAtIsNullOrderByPostedAtAsc(
                postId,
                FlyerVisibility.PUBLIC,
            ).map(FlyerLocationResponse::from)

    @Transactional
    fun updateStatus(
        flyerId: UUID,
        userId: UUID,
        status: FlyerStatus,
    ): FlyerLocationResponse {
        val flyer = getFlyer(flyerId)
        requireFlyerOwner(flyer, userId)
        flyer.toggle(status)
        return FlyerLocationResponse.from(flyer)
    }

    @Transactional
    fun updateVisibility(
        flyerId: UUID,
        userId: UUID,
        visibility: FlyerVisibility,
    ): FlyerLocationResponse {
        val flyer = getFlyer(flyerId)
        requireFlyerOwner(flyer, userId)
        flyer.updateVisibility(visibility)
        return FlyerLocationResponse.from(flyer)
    }

    @Transactional
    fun delete(
        flyerId: UUID,
        userId: UUID,
    ) {
        val flyer = getFlyer(flyerId)
        requireFlyerOwner(flyer, userId)
        flyerRepository.delete(flyer)
    }

    // ───── helpers ─────
    private fun getPost(postId: UUID): Post =
        postRepository.findById(postId).getOrNull()
            ?: throw BusinessException(ErrorCode.NOT_FOUND_POST)

    private fun getFlyer(flyerId: UUID): FlyerLocation =
        flyerRepository.findById(flyerId).getOrNull()
            ?: throw BusinessException(ErrorCode.NOT_FOUND_FLYER)

    private fun requireOwner(
        post: Post,
        userId: UUID,
    ) {
        if (post.authorId != userId) throw BusinessException(ErrorCode.FORBIDDEN)
    }

    private fun requireFlyerOwner(
        flyer: FlyerLocation,
        userId: UUID,
    ) {
        if (flyer.postedBy != userId) throw BusinessException(ErrorCode.FORBIDDEN)
    }
}
