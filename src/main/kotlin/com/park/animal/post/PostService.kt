package com.park.animal.post

import com.park.animal.bookmark.BookmarkService
import com.park.animal.breed.entity.AnimalType
import com.park.animal.breed.repository.BreedRepository
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.common.http.error.exception.ImageUploadException
import com.park.animal.multimedia.MultimediaService
import com.park.animal.notification.NotificationService
import com.park.animal.notification.entity.NotificationType
import com.park.animal.post.dto.PostDetailResponse
import com.park.animal.post.dto.PostNearbyResponse
import com.park.animal.post.dto.PostSummaryResponse
import com.park.animal.post.dto.RegisterPostCommand
import com.park.animal.post.dto.SummarizedPostsByPageDto
import com.park.animal.post.dto.SummarizedPostsByPageQuery
import com.park.animal.post.dto.UpdatePostRequest
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.entity.PostImage
import com.park.animal.post.repository.PostImageRepository
import com.park.animal.post.repository.PostNearbyRepository
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.SearchLifecycleService
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.util.UUID
import kotlin.jvm.optionals.getOrElse

@Service
class PostService(
    private val postRepository: PostRepository,
    private val multimediaService: MultimediaService,
    private val postImageRepository: PostImageRepository,
    private val breedRepository: BreedRepository,
    private val postNearbyRepository: PostNearbyRepository,
    private val notificationService: NotificationService,
    private val bookmarkService: BookmarkService,
    private val postWriteService: PostWriteService,
    private val searchLifecycleService: SearchLifecycleService,
    private val accessResolver: SearchGroupAccessResolver,
) {
    companion object {
        const val CANCEL_USER_NAME = "탈퇴한 사용자"
    }

    /**
     * 실종 소식 등록. `@Transactional` 은 여기 붙이지 않는다 — `suspend fun` 에는 트랜잭션이
     * 걸리지 않기 때문이다(F2). DB 단위작업은 [PostWriteService] 가 담당한다.
     */
    suspend fun registerPost(command: RegisterPostCommand) {
        validateBreed(command.animalType, command.breedId)
        withContext(Dispatchers.IO) {
            postWriteService.createPostWithSearchGroup(command, command.joinPolicy)
        }
    }

    private fun validateBreed(
        animalType: AnimalType,
        breedId: UUID?,
    ) {
        if (breedId == null) return
        val breed =
            breedRepository
                .findById(breedId)
                .orElseThrow { BusinessException(ErrorCode.NOT_FOUND_BREED) }
        if (breed.animalType != animalType) {
            throw BusinessException(ErrorCode.MISMATCHED_BREED)
        }
    }

    private suspend fun uploadImages(
        images: List<MultipartFile>,
        userId: UUID,
        applicationId: String,
    ): List<String> =
        multimediaService.uploadMultipartFiles(images, userId.toString(), applicationId)
            ?: throw ImageUploadException()

    private suspend fun savePostImages(
        post: Post,
        imageUrls: List<String>,
    ) {
        imageUrls.forEach { url ->
            val postImage =
                PostImage(
                    post = post,
                    imageUrl = url,
                )
            postImageRepository.save(postImage)
        }
    }

    suspend fun findDetailPost(
        id: UUID,
        userId: UUID?,
    ): PostDetailResponse {
        val detail =
            withContext(Dispatchers.IO) {
                postRepository.findPostDetailWithImages(id, userId)
            } ?: throw BusinessException(ErrorCode.NOT_FOUND_POST)
        val resolved = multimediaService.resolvePresignedUrls(detail.imageUrls.map { it.image })
        detail.imageUrls =
            detail.imageUrls.mapIndexed { idx, item ->
                item.copy(image = resolved[idx])
            }
        return detail
    }

    suspend fun findPostList(query: SummarizedPostsByPageQuery): SummarizedPostsByPageDto {
        val page =
            withContext(Dispatchers.IO) {
                postRepository.findSummarizedPostsByPage(
                    size = query.size,
                    orderBy = query.orderBy,
                    page = query.offset,
                )
            }
        val resolved = multimediaService.resolvePresignedUrls(page.result.map { it.thumbnail ?: "" })
        val newContents =
            page.result.mapIndexed { idx, item ->
                if (item.thumbnail.isNullOrBlank()) item else item.copy(thumbnail = resolved[idx])
            }
        return page.copy(result = newContents)
    }

    suspend fun findNearbyPosts(
        lat: Double,
        lng: Double,
        radiusKm: Double,
        size: Long,
        offset: Long,
    ): NearbyPostsPage {
        val contents =
            withContext(Dispatchers.IO) {
                postNearbyRepository.findNearby(lat, lng, radiusKm, size, offset)
            }
        val totalCount =
            withContext(Dispatchers.IO) {
                postNearbyRepository.countNearby(lat, lng, radiusKm)
            }
        val resolved = multimediaService.resolvePresignedUrls(contents.map { it.thumbnail ?: "" })
        val newContents =
            contents.mapIndexed { idx, item ->
                if (item.thumbnail.isNullOrBlank()) item else item.copy(thumbnail = resolved[idx])
            }
        val hasNextPage = totalCount > (offset + contents.size)
        return NearbyPostsPage(contents = newContents, hasNextPage = hasNextPage, totalCount = totalCount)
    }

    data class NearbyPostsPage(
        val contents: List<PostNearbyResponse>,
        val hasNextPage: Boolean,
        val totalCount: Long,
    )

    /**
     * 실종 소식 삭제. 그룹 보관을 **먼저** 수행한다 — `@SQLDelete` 는 in-memory `deletedAt` 을
     * 채우지 않아서(F4) 순서를 뒤집으면 삭제된 글의 그룹이 ACTIVE 로 남는다(설계 §14.2).
     */
    @Transactional
    fun deletePost(
        postId: UUID,
        userId: UUID,
    ) {
        val post = getPostEntity(postId)

        if (post.authorId != userId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
        searchLifecycleService.archiveOnPostDeleted(post, userId)
        // 조건부 벌크 UPDATE 가 1차 캐시를 비웠을 수 있으므로 관리 상태의 인스턴스를 다시 얻는다.
        val target = postRepository.findByIdAndDeletedAtIsNull(postId) ?: return
        postRepository.delete(target)
    }

    /**
     * 실종 소식 수정. `missingAnimalStatus` 는 여기서 직접 대입하지 않고
     * [SearchLifecycleService.applyStatusTransition] 에 위임한다(설계 §14.1) —
     * 이 API 로 `FOUND` 를 보내도 그룹 보관을 우회할 수 없어야 한다.
     *
     * 즐겨찾기 알림은 기존 동작을 그대로 보존한다: 이 경로는 원래 알림을 보내지 않았고,
     * 상태 변경 알림은 `PATCH /post/renewal-status` 의 책임이다.
     */
    @Transactional
    fun updatePost(
        command: UpdatePostRequest,
        userId: UUID,
    ) {
        val post = getPostEntity(command.postId)
        if (post.authorId != userId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
        validateBreed(command.animalType, command.breedId)
        val previous = post.missingAnimalStatus
        post.update(
            title = command.title,
            description = command.description,
            place = command.place,
            phoneNum = command.phoneNum,
            time = command.time,
            gender = command.gender,
            gratuity = command.gratuity,
            lat = command.lat,
            lng = command.lng,
            openChatUrl = command.openChatUrl,
            // 상태는 생명주기 서비스만 바꾼다. 여기서는 기존 값을 그대로 되돌려 놓는다.
            missingAnimalStatus = previous,
            animalType = command.animalType,
            breedId = command.breedId,
        )
        searchLifecycleService.applyStatusTransition(post, userId, command.missingAnimalStatus)
    }

    @Transactional
    fun addPostImage(
        images: List<MultipartFile>,
        postId: UUID,
        userId: UUID,
        applicationId: String,
    ) {
        val postEntity = getPostEntity(postId)
        if (postEntity.authorId != userId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
        runBlocking {
            val uploadImages = uploadImages(images, userId, applicationId)
            savePostImages(postEntity, uploadImages)
        }
    }

    @Transactional
    fun deletePostImage(
        userId: UUID,
        postImageId: UUID,
        postId: UUID,
    ) {
        val post = getPostEntity(postId)
        if (post.authorId != userId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
        val postImageEntity = getPostImageEntity(postImageId)
        postImageRepository.delete(postImageEntity)
    }

    /** soft-delete 된 글은 더 이상 변경 대상이 아니다(F3). */
    private fun getPostEntity(id: UUID) =
        postRepository.findByIdAndDeletedAtIsNull(id) ?: throw BusinessException(ErrorCode.NOT_FOUND_POST)

    private fun getPostImageEntity(id: UUID) =
        postImageRepository.findById(id).getOrElse {
            throw BusinessException(ErrorCode.NOT_FOUND_POST_IMAGE)
        }

    suspend fun myPage(userId: UUID): List<PostSummaryResponse> {
        val rows =
            withContext(Dispatchers.IO) {
                postRepository.findSummarizedPostsByUserId(userId)
            }
        val resolved = multimediaService.resolvePresignedUrls(rows.map { it.thumbnail ?: "" })
        return rows.mapIndexed { idx, item ->
            if (item.thumbnail.isNullOrBlank()) item else item.copy(thumbnail = resolved[idx])
        }
    }

    fun updateAuthor(
        userId: UUID,
        name: String,
    ) {
        postRepository.updateAuthorName(userId, name)
    }

    fun deleteAuthor(userId: UUID) {
        postRepository.updateAuthorName(userId, CANCEL_USER_NAME)
    }

    /**
     * 상태 전용 변경 API. 상태 전이는 [SearchLifecycleService] 에 위임하고
     * 기존 즐겨찾기 알림만 여기서 유지한다.
     */
    @Transactional
    fun updateStatus(
        postId: UUID,
        userId: UUID,
        status: MissingAnimalStatus,
    ) {
        val post = getPostEntity(postId)
        if (post.authorId != userId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
        val previous = post.missingAnimalStatus

        // 접근 판정은 native SQL 이라 auto-flush 되지 않는다 — 상태를 바꾸기 전에 계산한다.
        val alreadyNotified = effectiveMembersForEndSearch(post, status, userId)

        searchLifecycleService.applyStatusTransition(post, userId, status)

        if (previous != status) {
            notifyBookmarkers(post, userId, status, alreadyNotified)
        }
    }

    /**
     * 수색 종료(`FOUND`)로 `SEARCH_ENDED` 를 이미 받게 될 유효 참여자 집합.
     * 다른 상태 전이에는 그룹 알림이 없으므로 빈 집합이다.
     */
    private fun effectiveMembersForEndSearch(
        post: Post,
        next: MissingAnimalStatus,
        actorUserId: UUID,
    ): Set<UUID> {
        if (next != MissingAnimalStatus.FOUND) return emptySet()
        val access = accessResolver.resolveByPostId(post.id, actorUserId) ?: return emptySet()
        return accessResolver.effectiveMemberIds(access.groupId)
    }

    /**
     * 즐겨찾기한 사용자에게 상태 변경 알림 (작성자 본인 제외).
     * 수색그룹 유효 참여자는 이미 `SEARCH_ENDED` 를 받았으므로 제외한다 —
     * 설계 §9 "여러 경로로 같은 그룹 권한을 가진 사용자는 알림을 한 번만 받는다".
     *
     * TODO(Task 5): `createStructuredMany(..., postId = post.id)` 로 교체해 `notification.post_id` 를 채운다.
     */
    private fun notifyBookmarkers(
        post: Post,
        actorUserId: UUID,
        status: MissingAnimalStatus,
        alreadyNotified: Set<UUID>,
    ) {
        val bookmarkers = bookmarkService.findBookmarkUserIdsByPost(post.id)
        if (bookmarkers.isEmpty()) return
        val targets = bookmarkers.toSet() - alreadyNotified
        if (targets.isEmpty()) return

        notificationService.createMany(
            userIds = targets,
            excludeUserId = actorUserId,
            type = NotificationType.BOOKMARK_STATUS_CHANGED,
            title = "즐겨찾기 게시글 상태가 변경됐어요",
            body = "${post.title} → ${labelOf(status)}",
            link = "/lost/${post.id}",
        )
    }

    private fun labelOf(status: MissingAnimalStatus): String =
        when (status) {
            MissingAnimalStatus.SEARCHING -> "찾는 중"
            MissingAnimalStatus.FOUND -> "찾음"
            MissingAnimalStatus.SEEN -> "목격됨"
        }
}
