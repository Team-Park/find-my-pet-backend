package com.park.animal.post

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.common.http.error.exception.ImageUploadException
import com.park.animal.multimedia.MultimediaService
import com.park.animal.post.dto.PostDetailResponse
import com.park.animal.post.dto.RegisterPostCommand
import com.park.animal.post.dto.SummarizedPostsByPageDto
import com.park.animal.post.dto.SummarizedPostsByPageQuery
import com.park.animal.post.dto.UpdatePostRequest
import com.park.animal.post.entity.Post
import com.park.animal.post.entity.PostImage
import com.park.animal.post.repository.PostImageRepository
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
) {
    @Transactional
    suspend fun registerPost(command: RegisterPostCommand) {
        coroutineScope {
            val post = Post.createPostFromCommand(command)
            val postEntity = withContext(Dispatchers.IO) {
                postRepository.save(post)
            }
            if (command.images.isNotEmpty()) {
                val uploadImages = uploadImages(command.images)
                withContext(Dispatchers.IO) {
                    savePostImages(postEntity, uploadImages)
                }
            }
        }
    }

    private suspend fun uploadImages(images: List<MultipartFile>): List<String> {
        return multimediaService.uploadMultipartFiles(images) ?: throw ImageUploadException()
    }

    private suspend fun savePostImages(post: Post, imageUrls: List<String>) {
        imageUrls.forEach { url ->
            val postImage = PostImage(
                post = post,
                imageUrl = url,
            )
            postImageRepository.save(postImage)
        }
    }

    @Transactional(readOnly = true)
    fun findDetailPost(id: UUID): PostDetailResponse = postRepository.findPostDetailWithImages(id)
        ?: throw BusinessException(ErrorCode.NOT_FOUND_POST)

    @Transactional(readOnly = true)
    fun findPostList(query: SummarizedPostsByPageQuery): SummarizedPostsByPageDto {
        return postRepository.findSummarizedPostsByPage(
            size = query.size,
            orderBy = query.orderBy,
            offset = query.offset,
        )
    }

    @Transactional
    fun deletePost(postId: UUID, userId: UUID) {
        val post = getPostEntity(postId)

        if (post.author != userId) {
            throw BusinessException(ErrorCode.UNAUTHORIZED)
        }
        postRepository.delete(post)
    }

    @Transactional
    fun updatePost(command: UpdatePostRequest, userId: UUID) {
        val post = getPostEntity(command.postId)
        if (post.author != userId) {
            throw BusinessException(ErrorCode.UNAUTHORIZED)
        }
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
        )
    }

    @Transactional
    fun addPostImage(images: List<MultipartFile>, postId: UUID, userId: UUID) {
        val postEntity = getPostEntity(postId)
        if (postEntity.author != userId) {
            throw BusinessException(ErrorCode.UNAUTHORIZED)
        }
        runBlocking {
            val uploadImages = uploadImages(images)
            savePostImages(postEntity, uploadImages)
        }
    }

    @Transactional
    fun deletePostImage(userId: UUID, postImageId: UUID, postId: UUID) {
        val post = getPostEntity(postId)
        if (post.author != userId) {
            throw BusinessException(ErrorCode.UNAUTHORIZED)
        }
        val postImageEntity = getPostImageEntity(postImageId)
        postImageRepository.delete(postImageEntity)
    }

    private fun getPostEntity(id: UUID) =
        postRepository.findById(id).getOrNull() ?: throw BusinessException(ErrorCode.NOT_FOUND_POST)

    private fun getPostImageEntity(id: UUID) = postImageRepository.findById(id).getOrElse {
        throw BusinessException(ErrorCode.NOT_FOUND_POST_IMAGE)
    }
}
