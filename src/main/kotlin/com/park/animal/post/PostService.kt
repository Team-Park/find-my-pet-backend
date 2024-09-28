package com.park.animal.post

import com.park.animal.common.http.error.exception.ImageUploadException
import com.park.animal.multimedia.MultimediaService
import com.park.animal.post.dto.RegisterPostCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

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
            withContext(Dispatchers.IO) {
                postRepository.save(post)
            }
            if (command.images.isNotEmpty()) {
                val uploadImages = uploadImages(command.images)
                withContext(Dispatchers.IO) {
                    savePostImages(post, uploadImages)
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
}
