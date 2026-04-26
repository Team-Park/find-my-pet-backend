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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.util.UUID
import kotlin.jvm.optionals.getOrElse
import kotlin.jvm.optionals.getOrNull

@Service
class PostService(
    private val postRepository: PostRepository,
    private val multimediaService: MultimediaService,
    private val postImageRepository: PostImageRepository,
    private val breedRepository: BreedRepository,
    private val postNearbyRepository: PostNearbyRepository,
    private val notificationService: NotificationService,
    private val bookmarkService: BookmarkService,
) {
    companion object {
        const val CANCEL_USER_NAME = "탈퇴한 사용자"
    }

    @Transactional
    suspend fun registerPost(command: RegisterPostCommand) {
        validateBreed(command.animalType, command.breedId)
        coroutineScope {
            val post = Post.createPostFromCommand(command)
            val postEntity =
                withContext(Dispatchers.IO) {
                    postRepository.save(post)
                }
            if (command.images.isNotEmpty()) {
                val uploadImages = uploadImages(command.images, command.userId, command.applicationId)
                withContext(Dispatchers.IO) {
                    savePostImages(postEntity, uploadImages)
                }
            }
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

    @Transactional
    fun deletePost(
        postId: UUID,
        userId: UUID,
    ) {
        val post = getPostEntity(postId)

        if (post.authorId != userId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
        postRepository.delete(post)
    }

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
            missingAnimalStatus = command.missingAnimalStatus,
            animalType = command.animalType,
            breedId = command.breedId,
        )
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

    private fun getPostEntity(id: UUID) = postRepository.findById(id).getOrNull() ?: throw BusinessException(ErrorCode.NOT_FOUND_POST)

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
        post.updateStatus(status)

        // 즐겨찾기한 사용자들에게 상태 변경 알림 (작성자 본인은 제외).
        if (previous != status) {
            val bookmarkers = bookmarkService.findBookmarkUserIdsByPost(postId)
            if (bookmarkers.isNotEmpty()) {
                notificationService.createMany(
                    userIds = bookmarkers,
                    excludeUserId = userId,
                    type = NotificationType.BOOKMARK_STATUS_CHANGED,
                    title = "즐겨찾기 게시글 상태가 변경됐어요",
                    body = "${post.title} → ${labelOf(status)}",
                    link = "/lost/${post.id}",
                )
            }
        }
    }

    private fun labelOf(status: MissingAnimalStatus): String =
        when (status) {
            MissingAnimalStatus.SEARCHING -> "찾는 중"
            MissingAnimalStatus.FOUND -> "찾음"
            MissingAnimalStatus.SEEN -> "목격됨"
        }
}
