package com.park.animal.post

import com.park.animal.multimedia.MultimediaService
import com.park.animal.post.dto.RegisterPostCommand
import com.park.animal.post.entity.Post
import com.park.animal.post.entity.PostImage
import com.park.animal.post.repository.PostImageRepository
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.SearchLifecycleService
import com.park.animal.searchgroup.entity.JoinPolicy
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 실종 소식 등록의 트랜잭션 경계.
 *
 * 이 클래스가 존재하는 이유는 두 가지다.
 *
 * 1. **`suspend fun` 에는 `@Transactional` 이 걸리지 않는다(F2).** 기존
 *    `PostService.registerPost` 는 `@Transactional suspend fun` 이었고 내부에서
 *    `withContext(Dispatchers.IO)` 로 스레드를 갈아탔다. 트랜잭션은 코루틴이 시작되기 전에
 *    끝나 버리므로 실제로는 아무것도 원자적이지 않았다. DB 작업 단위는 non-suspend 빈에 둔다.
 * 2. **이미지 업로드 실패 시 post 가 남던 결함**을 이 구조가 바로잡는다. 업로드가 같은
 *    트랜잭션 안에서 일어나므로 [MultimediaService] 가 던지면 post·post_image·search_group 이
 *    전부 롤백된다. 이 동작은 `SearchLifecycleIT."이미지 업로드가 실패하면 post 도 수색그룹도 남지 않는다"`
 *    로 고정돼 있다.
 */
@Service
class PostWriteService(
    private val postRepository: PostRepository,
    private val postImageRepository: PostImageRepository,
    private val multimediaService: MultimediaService,
    private val searchLifecycleService: SearchLifecycleService,
) {
    /**
     * 실종 소식과 수색그룹을 한 트랜잭션에서 만든다.
     * `SEARCHING` 이 아니면 그룹은 만들지 않는다(설계 §6.1).
     */
    @Transactional
    fun createPostWithSearchGroup(
        command: RegisterPostCommand,
        joinPolicy: JoinPolicy,
    ): Post {
        // search_group 의 fk_search_group_post 가 검증되려면 부모 행이 먼저 DB 에 있어야 한다.
        // id 는 반드시 save 반환값에서 읽는다(F16).
        val post = postRepository.saveAndFlush(Post.createPostFromCommand(command))

        if (command.images.isNotEmpty()) {
            val imageUrls =
                runBlocking {
                    multimediaService.uploadMultipartFiles(
                        command.images,
                        command.userId.toString(),
                        command.applicationId,
                    )
                }
            imageUrls.forEach { url ->
                postImageRepository.save(PostImage(post = post, imageUrl = url))
            }
        }

        searchLifecycleService.openGroupForPost(post, joinPolicy)
        return post
    }
}
