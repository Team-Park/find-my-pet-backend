package com.park.animal.post

import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.JpaConfig
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.entity.PostImage
import com.park.animal.post.repository.PostImageRepository
import com.park.animal.post.repository.PostRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 삭제한 게시글이 상세 조회에서 실제로 사라지는지 고정한다.
 *
 * 2026-07-26 이전까지 `findPostDetail` 과 `findPostImages` 에만 `deleted_at` 필터가 빠져 있었다.
 * 목록 쿼리는 전부 걸고 있었으므로 이용자는 목록에서 사라진 것을 보고 삭제됐다고 믿지만,
 * `GET /post/{id}` 는 `@PublicEndPoint` 라서 **비로그인 상태로도** 전화번호·주소·좌표·설명이
 * 그대로 200 으로 내려왔다. 프론트가 이 응답을 SSR 하므로 이미 색인된 `/lost/{id}` 도 살아 있었다.
 *
 * 삭제가 접근을 끊지 못하면 처리방침이 안내하는 유일한 자력 구제 수단이 거짓이 된다. 이건
 * 회귀하면 조용히 개인정보가 새는 종류라, 목록 필터와 별개로 상세 경로를 못박아 둔다.
 *
 * `@SQLDelete` 가 실제 UPDATE 를 쏘고 그 결과를 다시 조회해야 하므로 테스트 트랜잭션 래핑을 끈다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaConfig::class)
@Testcontainers
class PostDetailSoftDeleteIT {
    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var postImageRepository: PostImageRepository

    private fun newPost(title: String) =
        Post(
            authorId = UUID.randomUUID(),
            authorName = "테스터",
            title = title,
            phoneNum = "01012345678",
            time = LocalDateTime.now(),
            place = "서울 강남구 역삼동",
            gender = "남아",
            gratuity = 0,
            description = "겁이 많은 하얀 말티즈입니다.",
            lat = 37.5012,
            lng = 127.0396,
            openChatUrl = null,
            missingAnimalStatus = MissingAnimalStatus.SEARCHING,
            animalType = AnimalType.DOG,
        )

    @Test
    fun `삭제한 게시글은 상세 조회에서 사라진다 - 전화번호가 계속 내려가면 안 된다`() {
        val post = postRepository.save(newPost("삭제 대상 게시글"))

        val before = postRepository.findPostDetailWithImages(post.id, null)
        assertNotNull(before, "삭제 전에는 조회돼야 한다 — 그래야 이 테스트가 삭제를 검증한다")
        assertEquals("01012345678", before.phoneNum)

        postRepository.delete(post)

        assertNull(
            postRepository.findPostDetailWithImages(post.id, null),
            "삭제 후에도 상세가 조회되면 비로그인 사용자에게 전화번호·주소·좌표가 계속 노출된다",
        )
    }

    @Test
    fun `삭제한 사진은 상세 응답의 이미지 목록에서 빠진다`() {
        val post = postRepository.save(newPost("사진 삭제 대상"))
        val kept = postImageRepository.save(PostImage(post = post, imageUrl = "post/kept"))
        val removed = postImageRepository.save(PostImage(post = post, imageUrl = "post/removed"))

        assertEquals(2, postRepository.findPostDetailWithImages(post.id, null)?.imageUrls?.size)

        postImageRepository.delete(removed)

        val images = postRepository.findPostDetailWithImages(post.id, null)?.imageUrls
        assertEquals(listOf(kept.id), images?.map { it.id }, "삭제한 사진이 상세 응답에 남아 있다")
    }

    @Test
    fun `삭제하지 않은 게시글은 영향받지 않는다`() {
        val survivor = postRepository.save(newPost("살아있는 게시글"))
        val victim = postRepository.save(newPost("지울 게시글"))

        postRepository.delete(victim)

        assertNotNull(
            postRepository.findPostDetailWithImages(survivor.id, null),
            "필터를 너무 넓게 걸어 멀쩡한 게시글까지 사라지면 그것도 회귀다",
        )
    }

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                )

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            // 운영 마이그레이션 체인(V2 빈 파일 = legacy 스키마) 보완: 테스트 전용 V2.1 베이스라인 추가
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
