package com.park.animal.post

import com.park.animal.multimedia.MultimediaService
import com.park.animal.post.dto.RegisterPostCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PostService(
    private val postRepository: PostRepository,
    private val multimediaService: MultimediaService,
    private val postImageRepository: PostImageRepository,
) {
    @Transactional
    suspend fun registerPost(command: RegisterPostCommand) {
        coroutineScope {
            val post = Post(
                title = command.title,
                time = command.time,
                phoneNum = command.phoneNum,
                place = command.place,
                description = command.description,
                gender = command.gender,
                gratuity = command.gratuity,
            )
            withContext(Dispatchers.IO) {
                postRepository.save(post)
            }
            if (command.images.isNotEmpty()) {
                val uploadResults = command.images.map { image ->
                    async { multimediaService.uploadMultipartFile(image) }
                }.awaitAll()
                withContext(Dispatchers.IO) {
                    uploadResults.filterNotNull().forEach { url ->
                        val postImage = PostImage(post = post, imageUrl = url)
                        postImageRepository.save(postImage)
                    }
                }
            }
        }
    }
}
