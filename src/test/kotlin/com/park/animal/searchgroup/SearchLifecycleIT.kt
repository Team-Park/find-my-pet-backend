package com.park.animal.searchgroup

import com.park.animal.bookmark.BookmarkService
import com.park.animal.bookmark.repository.PostBookmarkRepository
import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.common.http.error.exception.ImageUploadException
import com.park.animal.multimedia.MultimediaService
import com.park.animal.notification.NotificationService
import com.park.animal.notification.entity.NotificationType
import com.park.animal.notification.repository.NotificationRepository
import com.park.animal.post.PostService
import com.park.animal.post.PostWriteService
import com.park.animal.post.dto.RegisterPostCommand
import com.park.animal.post.dto.UpdatePostRequest
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.repository.PostNearbyRepository
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.ArchivedReason
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupMember
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupEventRepository
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 테스트 전용 대체 빈.
 *
 * - `MultimediaService` 는 외부 StorageClient(MinIO) 에 붙으므로 mock 으로 대체한다.
 * - `MeterRegistry` 는 `SearchGroupAccessResolver` 가 주입받는데 `@DataJpaTest` 는
 *   actuator auto-configuration 을 포함하지 않으므로 in-memory 레지스트리를 직접 공급한다.
 *
 * `@DataJpaTest` 는 `@Service` 를 컴포넌트 스캔하지 않으므로 실제 빈과 충돌하지 않는다.
 */
@TestConfiguration
class SearchLifecycleTestBeans {
    @Bean
    fun multimediaService(): MultimediaService = mock()

    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}

/**
 * 수색 생명주기 통합 검증 (설계 §14.1, §14.2).
 *
 * 트랜잭션 경계 자체가 검증 대상이므로 테스트를 트랜잭션으로 감싸지 않는다(NOT_SUPPORTED).
 * 감싸면 "이미지 업로드 실패 시 post 가 롤백된다" 를 절대 재현할 수 없다.
 * 컨트롤러는 서비스로의 얇은 위임이므로 서비스 진입점을 직접 구동한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    SearchLifecycleTestBeans::class,
    PostNearbyRepository::class,
    NotificationService::class,
    BookmarkService::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchGroupEventRecorder::class,
    SearchLifecycleService::class,
    PostWriteService::class,
    PostService::class,
)
@Testcontainers
class SearchLifecycleIT {
    @Autowired lateinit var postService: PostService

    @Autowired lateinit var lifecycleService: SearchLifecycleService

    @Autowired lateinit var multimediaService: MultimediaService

    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var searchGroupRepository: SearchGroupRepository

    @Autowired lateinit var searchGroupEventRepository: SearchGroupEventRepository

    @Autowired lateinit var searchGroupMemberRepository: SearchGroupMemberRepository

    @Autowired lateinit var notificationRepository: NotificationRepository

    @Autowired lateinit var postBookmarkRepository: PostBookmarkRepository

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private val owner: UUID = UUID.randomUUID()

    @BeforeEach
    fun clean() {
        listOf(
            "search_group_event",
            "search_group_team",
            "search_group_member",
            "search_group_user_block",
            "team_member",
            "team",
            "search_group",
            "notification",
            "post_bookmark",
            "post_image",
            "post",
        ).forEach { jdbcTemplate.update("DELETE FROM $it") }
        stubUploadSuccess()
    }

    // (a) 등록 시 그룹 생성 규칙

    @Test
    fun `SEARCHING 실종 소식을 등록하면 수색그룹이 정확히 하나 생긴다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)

        assertEquals(1L, searchGroupRepository.count())
        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!
        assertEquals(SearchGroupStatus.ACTIVE, group.status)
        assertEquals(JoinPolicy.OPEN, group.joinPolicy)
        assertNull(group.archivedReason)
        assertEquals(1L, searchGroupEventRepository.count())
        assertEquals(SearchGroupEventType.GROUP_OPENED, searchGroupEventRepository.findAll().first().type)
    }

    @Test
    fun `APPROVAL_REQUIRED 로 등록하면 그 정책 그대로 그룹이 생긴다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.APPROVAL_REQUIRED)

        assertEquals(
            JoinPolicy.APPROVAL_REQUIRED,
            searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!.joinPolicy,
        )
    }

    @Test
    fun `SEEN 목격 소식에는 수색그룹을 만들지 않는다`() {
        registerPost(MissingAnimalStatus.SEEN, JoinPolicy.OPEN)

        assertEquals(0L, searchGroupRepository.count())
        assertEquals(0L, searchGroupEventRepository.count())
    }

    @Test
    fun `SEEN 으로 등록한 글이 SEARCHING 이 되면 그때 OPEN 그룹이 생긴다`() {
        val postId = registerPost(MissingAnimalStatus.SEEN, JoinPolicy.OPEN)

        postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.SEARCHING)

        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!
        assertEquals(JoinPolicy.OPEN, group.joinPolicy)
        assertEquals(SearchGroupStatus.ACTIVE, group.status)
    }

    // (b) PATCH /post/renewal-status

    @Test
    fun `상태 변경 API 로 FOUND 가 되면 그룹이 ARCHIVED FOUND 로 보관된다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)

        postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.FOUND)

        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!
        assertEquals(SearchGroupStatus.ARCHIVED, group.status)
        assertEquals(ArchivedReason.FOUND, group.archivedReason)
        assertEquals(owner, group.archivedBy)
        assertNotNull(group.archivedAt)
        assertEquals(
            MissingAnimalStatus.FOUND,
            postRepository.findByIdAndDeletedAtIsNull(postId)!!.missingAnimalStatus,
        )
    }

    @Test
    fun `SEARCHING 에서 SEEN 으로 바뀌어도 그룹은 ACTIVE 로 유지된다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)

        postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.SEEN)

        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!
        assertEquals(SearchGroupStatus.ACTIVE, group.status)
        assertNull(group.archivedReason)
    }

    @Test
    fun `종료된 수색을 다시 SEARCHING 으로 되돌리려 하면 410`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)
        postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.FOUND)

        val e =
            assertFailsWith<BusinessException> {
                postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.SEARCHING)
            }

        assertEquals(ErrorCode.SEARCH_ALREADY_ENDED, e.errorCode)
        assertEquals(
            SearchGroupStatus.ARCHIVED,
            searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!.status,
        )
    }

    @Test
    fun `종료된 수색을 SEEN 으로 되돌리려 해도 410`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)
        postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.FOUND)

        val e =
            assertFailsWith<BusinessException> {
                postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.SEEN)
            }

        assertEquals(ErrorCode.SEARCH_ALREADY_ENDED, e.errorCode)
        assertEquals(
            MissingAnimalStatus.FOUND,
            postRepository.findByIdAndDeletedAtIsNull(postId)!!.missingAnimalStatus,
            "거부된 전이가 post 상태를 조용히 SEEN 으로 바꾸면 안 된다 — 그러면 이후 재-FOUND 호출이 " +
                "endSearch 의 조건부 UPDATE(0행, 이미 ARCHIVED)에 막혀 post 가 SEEN 인 채로 200 을 반환한다",
        )
    }

    @Test
    fun `SEARCHING to FOUND to SEEN 순서에서 SEEN 이 거부돼 상태와 알림이 오염되지 않는다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)
        val groupId = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!.id
        val participant = UUID.randomUUID()
        joinAsActiveMember(groupId, participant)
        bookmark(participant, postId)

        postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.FOUND)
        assertEquals(
            1,
            notificationRepository.findAll().count { it.userId == participant },
            "FOUND 전환에서 유효 참여자는 SEARCH_ENDED 를 정확히 한 건 받는다",
        )

        assertFailsWith<BusinessException> {
            postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.SEEN)
        }

        val post = postRepository.findByIdAndDeletedAtIsNull(postId)!!
        assertEquals(MissingAnimalStatus.FOUND, post.missingAnimalStatus, "SEEN 이 거부됐으니 post 는 FOUND 를 유지한다")
        assertEquals(
            SearchGroupStatus.ARCHIVED,
            searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!.status,
        )
        assertEquals(
            1,
            notificationRepository.findAll().count { it.userId == participant },
            "SEEN 이 조기에 거부되므로 그 다음 재-FOUND 호출 자체가 일어나지 않는다 — 재-FOUND 가 " +
                "effectiveMemberIds 를 alreadyNotified 로 계산해 놓고 endSearch 의 조건부 UPDATE 가 " +
                "0행이라 실제로는 SEARCH_ENDED 를 보내지 않으면서 BOOKMARK_STATUS_CHANGED 도 " +
                "'이미 알림 받음'으로 억제해 참여자가 아무 알림도 못 받는 상황이 재현되지 않는다",
        )
    }

    // (c) PUT /post 로도 우회 불가

    @Test
    fun `게시글 수정 API 로 FOUND 를 보내도 그룹이 ARCHIVED 된다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)

        postService.updatePost(command = updateRequest(postId, MissingAnimalStatus.FOUND), userId = owner)

        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!
        assertEquals(SearchGroupStatus.ARCHIVED, group.status)
        assertEquals(ArchivedReason.FOUND, group.archivedReason)
        val post = postRepository.findByIdAndDeletedAtIsNull(postId)!!
        assertEquals(MissingAnimalStatus.FOUND, post.missingAnimalStatus)
        assertEquals("수정된 제목", post.title, "일반 필드 수정도 같은 트랜잭션에서 반영돼야 한다")
    }

    // (d) DELETE /post

    @Test
    fun `실종 소식을 삭제하면 그룹이 POST_DELETED 로 보관된다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)

        postService.deletePost(postId = postId, userId = owner)

        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!
        assertEquals(SearchGroupStatus.ARCHIVED, group.status)
        assertEquals(ArchivedReason.POST_DELETED, group.archivedReason)
        assertNull(postRepository.findByIdAndDeletedAtIsNull(postId), "post 는 soft-delete 된다")
        assertTrue(
            searchGroupEventRepository.findAll().any { it.type == SearchGroupEventType.GROUP_ARCHIVED_BY_POST_DELETE },
        )
    }

    @Test
    fun `soft-delete 된 실종 소식은 더 이상 상태를 바꿀 수 없다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)
        postService.deletePost(postId = postId, userId = owner)

        val e =
            assertFailsWith<BusinessException> {
                postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.FOUND)
            }

        assertEquals(ErrorCode.NOT_FOUND_POST, e.errorCode)
    }

    // (e) 멱등성

    @Test
    fun `수색 종료를 두 번 호출해도 감사와 알림은 각각 한 건이다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)
        val groupId = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!.id
        val participant = UUID.randomUUID()
        joinAsActiveMember(groupId, participant)

        lifecycleService.endSearch(groupId, owner)
        val group = lifecycleService.endSearch(groupId, owner)

        assertEquals(SearchGroupStatus.ARCHIVED, group.status, "두 번째 호출도 예외 없이 현재 상태를 돌려준다")
        assertEquals(1, searchGroupEventRepository.findAll().count { it.type == SearchGroupEventType.SEARCH_ENDED })
        assertEquals(1, notificationRepository.findAll().count { it.userId == participant })
    }

    // (f) 트랜잭션 경계 회귀 방지

    @Test
    fun `이미지 업로드가 실패하면 post 도 수색그룹도 남지 않는다`() {
        stubUploadFailure()

        assertFailsWith<ImageUploadException> {
            runBlocking {
                postService.registerPost(
                    registerCommand(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN, withImage = true),
                )
            }
        }

        assertEquals(0L, postRepository.count(), "suspend 함수에 @Transactional 을 붙이면 여기서 post 가 남는다(F2)")
        assertEquals(0L, searchGroupRepository.count())
        assertEquals(0L, searchGroupEventRepository.count())
    }

    @Test
    fun `이미지 업로드는 DB 트랜잭션 밖에서 post 저장보다 먼저 끝난다`() {
        // MinIO 업로드 호출 시점의 상태를 그 자리에서 캡처한다 — 나중에 다시 조회하면 이미
        // 트랜잭션이 커밋된 뒤라 순서를 증명할 수 없다.
        var transactionActiveDuringUpload: Boolean? = null
        var postCountDuringUpload: Long? = null
        runBlocking {
            whenever(multimediaService.uploadMultipartFiles(any(), any(), any())).thenAnswer {
                transactionActiveDuringUpload = TransactionSynchronizationManager.isActualTransactionActive()
                postCountDuringUpload = postRepository.count()
                listOf("test-bucket/post/${UUID.randomUUID()}")
            }
        }

        runBlocking {
            postService.registerPost(
                registerCommand(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN, withImage = true),
            )
        }

        assertEquals(
            false,
            transactionActiveDuringUpload,
            "업로드 호출 시점에 Spring 트랜잭션이 열려 있으면 안 된다 — 열려 있으면 그 트랜잭션이 " +
                "Hikari 커넥션(풀 크기 20)을 오브젝트 스토리지 왕복 내내 붙잡는다",
        )
        assertEquals(
            0L,
            postCountDuringUpload,
            "업로드는 post 저장보다 먼저 끝나야 한다 — 업로드 시점에 이미 post 행이 보이면 " +
                "PostWriteService 의 트랜잭션이 업로드를 감싸고 있다는 뜻이다",
        )
        assertEquals(1L, postRepository.count(), "업로드가 끝난 뒤에는 post 가 정상적으로 생성된다")
    }

    // (g) 알림 중복 제거

    @Test
    fun `즐겨찾기와 참여를 겸한 사용자는 수색 종료 알림을 정확히 한 건만 받는다`() {
        val postId = registerPost(MissingAnimalStatus.SEARCHING, JoinPolicy.OPEN)
        val groupId = searchGroupRepository.findByPostIdAndDeletedAtIsNull(postId)!!.id
        val both = UUID.randomUUID()
        val bookmarkerOnly = UUID.randomUUID()
        joinAsActiveMember(groupId, both)
        bookmark(both, postId)
        bookmark(bookmarkerOnly, postId)

        postService.updateStatus(postId = postId, userId = owner, status = MissingAnimalStatus.FOUND)

        val forBoth = notificationRepository.findAll().filter { it.userId == both }
        assertEquals(1, forBoth.size, "설계 §9 — 여러 경로로 권한을 가진 사용자는 한 번만 받는다")
        assertEquals(NotificationType.SEARCH_ENDED, forBoth.first().type)

        val forBookmarker = notificationRepository.findAll().filter { it.userId == bookmarkerOnly }
        assertEquals(1, forBookmarker.size)
        assertEquals(
            NotificationType.BOOKMARK_STATUS_CHANGED,
            forBookmarker.first().type,
            "참여자가 아닌 즐겨찾기 사용자는 기존 알림을 그대로 받는다",
        )

        assertEquals(0, notificationRepository.findAll().count { it.userId == owner }, "행위자 본인은 제외")
    }

    // --- helper ---

    private fun stubUploadSuccess() {
        runBlocking {
            whenever(multimediaService.uploadMultipartFiles(any(), any(), any()))
                .thenReturn(listOf("test-bucket/post/${UUID.randomUUID()}"))
        }
    }

    private fun stubUploadFailure() {
        runBlocking {
            whenever(multimediaService.uploadMultipartFiles(any(), any(), any()))
                .thenThrow(ImageUploadException())
        }
    }

    private fun registerCommand(
        status: MissingAnimalStatus,
        joinPolicy: JoinPolicy,
        withImage: Boolean = false,
    ): RegisterPostCommand =
        RegisterPostCommand(
            userId = owner,
            userName = "보호자",
            images =
                if (withImage) {
                    listOf(MockMultipartFile("image", "a.png", "image/png", byteArrayOf(1, 2, 3)))
                } else {
                    emptyList()
                },
            title = "말티즈를 찾습니다",
            phoneNum = "010-0000-0000",
            time = LocalDateTime.now(),
            place = "서울 강남구",
            gender = "남아",
            gratuity = 0,
            description = "겁이 많아요",
            lat = 37.5,
            lng = 127.0,
            openChatUrl = null,
            missingAnimalStatus = status,
            animalType = AnimalType.DOG,
            breedId = null,
            applicationId = "test-app",
            joinPolicy = joinPolicy,
        )

    private fun registerPost(
        status: MissingAnimalStatus,
        joinPolicy: JoinPolicy,
    ): UUID {
        runBlocking { postService.registerPost(registerCommand(status, joinPolicy)) }
        return postRepository.findAll().first { it.deletedAt == null }.id
    }

    private fun updateRequest(
        postId: UUID,
        status: MissingAnimalStatus,
    ): UpdatePostRequest =
        UpdatePostRequest(
            postId = postId,
            title = "수정된 제목",
            phoneNum = "010-0000-0000",
            time = LocalDateTime.now(),
            place = "서울 강남구",
            gender = "남아",
            gratuity = 0,
            description = "겁이 많아요",
            lat = 37.5,
            lng = 127.0,
            openChatUrl = null,
            missingAnimalStatus = status,
            animalType = AnimalType.DOG,
            breedId = null,
        )

    private fun joinAsActiveMember(
        groupId: UUID,
        userId: UUID,
    ) {
        searchGroupMemberRepository.save(
            SearchGroupMember(
                groupId = groupId,
                userId = userId,
                userName = null,
                status = SearchGroupMemberStatus.ACTIVE,
                joinedAt = LocalDateTime.now(),
                requestedAt = null,
            ),
        )
    }

    private fun bookmark(
        userId: UUID,
        postId: UUID,
    ) {
        postBookmarkRepository.save(com.park.animal.bookmark.entity.PostBookmark(userId = userId, postId = postId))
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
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
