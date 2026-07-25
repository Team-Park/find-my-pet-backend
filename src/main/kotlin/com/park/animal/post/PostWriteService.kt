package com.park.animal.post

import com.park.animal.post.dto.RegisterPostCommand
import com.park.animal.post.entity.Post
import com.park.animal.post.entity.PostImage
import com.park.animal.post.repository.PostImageRepository
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.SearchLifecycleService
import com.park.animal.searchgroup.entity.JoinPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 실종 소식 등록의 트랜잭션 경계.
 *
 * 이 클래스가 존재하는 이유는 다음과 같다.
 *
 * 1. **`suspend fun` 에는 `@Transactional` 이 걸리지 않는다(F2).** 기존
 *    `PostService.registerPost` 는 `@Transactional suspend fun` 이었고 내부에서
 *    `withContext(Dispatchers.IO)` 로 스레드를 갈아탔다. 트랜잭션은 코루틴이 시작되기 전에
 *    끝나 버리므로 실제로는 아무것도 원자적이지 않았다. DB 작업 단위는 non-suspend 빈에 둔다.
 * 2. **MinIO 업로드는 이 트랜잭션 밖에서 끝나 있어야 한다.** 초기 구현은 업로드를 이
 *    `@Transactional` 안에서 돌려 "업로드 실패 시 post 가 고아로 남는" 결함을 고쳤지만, 그 대신
 *    수 메가바이트 오브젝트 스토리지 왕복 내내 Hikari 커넥션(풀 크기 20)과 새 post 행의 쓰기
 *    락을 붙잡고 있었다. 코디네이터 리뷰 이후 사람이 이 트레이드오프를 뒤집었다: **고아 blob 이
 *    고아 post 보다 훨씬 싸다.** 그래서 업로드는 [PostService.registerPost] 에서 이 메서드를
 *    호출하기 **전에**, 트랜잭션도 커넥션도 없는 상태로 먼저 끝낸다. 이 메서드는 이미 업로드된
 *    URL 목록을 받아 `post`/`post_image`/`search_group` 세 테이블만 짧게 쓴다.
 *
 * **받아들인 트레이드오프**: 업로드가 성공한 직후, 이 트랜잭션이 커밋되기 전에 프로세스가 죽으면
 * MinIO 에는 어떤 `post_image` 행도 가리키지 않는 오브젝트가 하나 남는다. 이 상태는 의도적으로
 * 방치한다 — 되돌려서 "고치지" 말 것. 참조되지 않는 blob 은 스토리지 비용일 뿐이고, 정합성이
 * 깨지는 다른 경로(고아 post, 오래 붙잡힌 커넥션)보다 훨씬 다루기 쉽다.
 */
@Service
class PostWriteService(
    private val postRepository: PostRepository,
    private val postImageRepository: PostImageRepository,
    private val searchLifecycleService: SearchLifecycleService,
) {
    /**
     * 실종 소식과 수색그룹을 한 트랜잭션에서 만든다. `imageUrls` 는 호출부가 **이미 업로드를
     * 끝낸** 결과다 — 이 메서드 안에서는 네트워크 호출을 절대 하지 않는다.
     * `SEARCHING` 이 아니면 그룹은 만들지 않는다(설계 §6.1).
     */
    @Transactional
    fun createPostWithSearchGroup(
        command: RegisterPostCommand,
        joinPolicy: JoinPolicy,
        imageUrls: List<String>,
    ): Post {
        // search_group 의 fk_search_group_post 가 검증되려면 부모 행이 먼저 DB 에 있어야 한다.
        // id 는 반드시 save 반환값에서 읽는다(F16).
        val post = postRepository.saveAndFlush(Post.createPostFromCommand(command))

        imageUrls.forEach { url ->
            postImageRepository.save(PostImage(post = post, imageUrl = url))
        }

        searchLifecycleService.openGroupForPost(post, joinPolicy)
        return post
    }
}
