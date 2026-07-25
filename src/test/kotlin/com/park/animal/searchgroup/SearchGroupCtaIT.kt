package com.park.animal.searchgroup

import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.NotificationService
import com.park.animal.notification.entity.NotificationType
import com.park.animal.notification.repository.NotificationRepository
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.access.GroupRole
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.dto.SearchGroupCtaResponse
import com.park.animal.searchgroup.dto.SearchGroupViewerAction
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestConfiguration
class SearchGroupCtaTestBeans {
    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}

/**
 * 공개 CTA · 그룹 상세 · 수색 종료 · 활동 기록 (계약 §8 #2·#3·#5·#6, 설계 §11/§14.1/§20).
 *
 * 시드는 JdbcTemplate 로 직접 INSERT 한다 — 접근 판정이 native SQL 이라 커밋된 행만 보이고,
 * 여기서 검증하려는 것은 "테이블 상태가 이럴 때 어떤 viewerAction 이 나오는가" 이기 때문이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    SearchGroupCtaTestBeans::class,
    NotificationService::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchGroupEventRecorder::class,
    SearchLifecycleService::class,
    SearchGroupService::class,
)
@Testcontainers
class SearchGroupCtaIT {
    @Autowired lateinit var searchGroupService: SearchGroupService

    @Autowired lateinit var notificationRepository: NotificationRepository

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
            "post_image",
            "post",
        ).forEach { jdbcTemplate.update("DELETE FROM $it") }
    }

    // (a) 공개 CTA — 설계 §11 카카오톡 공유 링크 방문자 흐름

    @Test
    fun `비로그인 방문자는 LOGIN_REQUIRED 를 받는다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        insertMember(groupId, UUID.randomUUID(), SearchGroupMemberStatus.ACTIVE)

        val cta = searchGroupService.getCta(postId, null)

        assertEquals(SearchGroupViewerAction.LOGIN_REQUIRED, cta.viewerAction)
        assertEquals(groupId, cta.groupId)
        assertEquals(1L, cta.memberCount)
        assertEquals(0L, cta.teamCount)
    }

    @Test
    fun `자유 참여 그룹의 비참여 로그인 사용자는 JOIN_NOW 를 받는다`() {
        val postId = insertPost(owner)
        insertGroup(postId, joinPolicy = JoinPolicy.OPEN)

        val cta = searchGroupService.getCta(postId, UUID.randomUUID())

        assertEquals(SearchGroupViewerAction.JOIN_NOW, cta.viewerAction)
    }

    @Test
    fun `승인 후 참여 그룹의 비참여 로그인 사용자는 REQUEST_JOIN 을 받는다`() {
        val postId = insertPost(owner)
        insertGroup(postId, joinPolicy = JoinPolicy.APPROVAL_REQUIRED)

        val cta = searchGroupService.getCta(postId, UUID.randomUUID())

        assertEquals(SearchGroupViewerAction.REQUEST_JOIN, cta.viewerAction)
    }

    @Test
    fun `보호자와 참여자는 ALREADY_JOINED 를 받는다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val participant = UUID.randomUUID()
        insertMember(groupId, participant, SearchGroupMemberStatus.ACTIVE)

        assertEquals(SearchGroupViewerAction.ALREADY_JOINED, searchGroupService.getCta(postId, owner).viewerAction)
        assertEquals(
            SearchGroupViewerAction.ALREADY_JOINED,
            searchGroupService.getCta(postId, participant).viewerAction,
        )
    }

    @Test
    fun `차단된 사용자는 UNAVAILABLE 을 받고 응답에 차단 사실이 드러나지 않는다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val blocked = UUID.randomUUID()
        insertMember(groupId, blocked, SearchGroupMemberStatus.ACTIVE)
        insertBlock(groupId, blocked, owner)

        val cta = searchGroupService.getCta(postId, blocked)

        assertEquals(SearchGroupViewerAction.UNAVAILABLE, cta.viewerAction)
        assertTrue(
            SearchGroupCtaResponse::class.memberProperties.none { it.name.contains("block", ignoreCase = true) },
            "설계 §6.3 / 계약 §9 — CTA 응답에 차단 여부 필드를 두지 않는다",
        )
    }

    @Test
    fun `ARCHIVED 그룹의 CTA 는 누구에게나 UNAVAILABLE 이다`() {
        val postId = insertPost(owner, status = MissingAnimalStatus.FOUND)
        val groupId = insertGroup(postId, status = SearchGroupStatus.ARCHIVED)
        val participant = UUID.randomUUID()
        insertMember(groupId, participant, SearchGroupMemberStatus.ACTIVE)

        assertEquals(SearchGroupViewerAction.UNAVAILABLE, searchGroupService.getCta(postId, null).viewerAction)
        assertEquals(SearchGroupViewerAction.UNAVAILABLE, searchGroupService.getCta(postId, owner).viewerAction)
        assertEquals(SearchGroupViewerAction.UNAVAILABLE, searchGroupService.getCta(postId, participant).viewerAction)
    }

    @Test
    fun `그룹이 없는 실종 소식과 삭제된 실종 소식의 CTA 는 404`() {
        val noGroupPostId = insertPost(owner, status = MissingAnimalStatus.SEEN)
        val deletedPostId = insertPost(owner, deleted = true)
        insertGroup(deletedPostId)

        val noGroup = assertFailsWith<BusinessException> { searchGroupService.getCta(noGroupPostId, owner) }
        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP, noGroup.errorCode)

        val deleted = assertFailsWith<BusinessException> { searchGroupService.getCta(deletedPostId, owner) }
        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP, deleted.errorCode)
    }

    // (b) 그룹 상세

    @Test
    fun `보호자 상세에는 관리 권한과 대기 건수가 담긴다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        insertMember(groupId, UUID.randomUUID(), SearchGroupMemberStatus.ACTIVE)
        insertMember(groupId, UUID.randomUUID(), SearchGroupMemberStatus.PENDING)

        val detail = searchGroupService.getDetail(groupId, owner)

        assertEquals(GroupRole.OWNER, detail.role)
        assertEquals(SearchGroupStatus.ACTIVE, detail.status)
        assertEquals(1L, detail.memberCount)
        assertEquals(1L, detail.pendingMemberCount)
        assertTrue(detail.canManage)
        assertTrue(detail.canWrite)
        assertEquals("테스트 실종 소식", detail.postTitle)
    }

    @Test
    fun `참여자 상세에는 대기 건수를 노출하지 않는다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val participant = UUID.randomUUID()
        val membershipId = insertMember(groupId, participant, SearchGroupMemberStatus.ACTIVE)
        insertMember(groupId, UUID.randomUUID(), SearchGroupMemberStatus.PENDING)

        val detail = searchGroupService.getDetail(groupId, participant)

        assertEquals(GroupRole.PARTICIPANT, detail.role)
        assertEquals(membershipId, detail.myMembershipId)
        assertEquals(SearchGroupMemberStatus.ACTIVE, detail.myMembershipStatus)
        assertNull(detail.pendingMemberCount, "확인할 요청은 보호자 전용이다")
        assertNull(detail.pendingTeamCount)
        assertEquals(false, detail.canManage)
    }

    @Test
    fun `비참여자의 상세 조회는 403`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)

        val e = assertFailsWith<BusinessException> { searchGroupService.getDetail(groupId, UUID.randomUUID()) }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
    }

    // (c) 수색 종료

    @Test
    fun `수색 종료는 보호자만 할 수 있다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val participant = UUID.randomUUID()
        insertMember(groupId, participant, SearchGroupMemberStatus.ACTIVE)

        val e = assertFailsWith<BusinessException> { searchGroupService.endSearch(groupId, participant) }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
        assertEquals(0, notificationRepository.findAll().size)
    }

    @Test
    fun `수색 종료를 두 번 호출해도 알림은 한 건이고 두 번째는 410`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val participant = UUID.randomUUID()
        insertMember(groupId, participant, SearchGroupMemberStatus.ACTIVE)

        val detail = searchGroupService.endSearch(groupId, owner)

        assertEquals(SearchGroupStatus.ARCHIVED, detail.status)
        assertEquals(MissingAnimalStatus.FOUND, detail.postStatus)
        assertEquals(false, detail.canWrite)

        val second = assertFailsWith<BusinessException> { searchGroupService.endSearch(groupId, owner) }
        assertEquals(ErrorCode.SEARCH_ALREADY_ENDED, second.errorCode)

        val forParticipant = notificationRepository.findAll().filter { it.userId == participant }
        assertEquals(1, forParticipant.size)
        assertEquals(NotificationType.SEARCH_ENDED, forParticipant.first().type)
        assertEquals(0, notificationRepository.findAll().count { it.userId == owner }, "행위자 본인은 제외")
    }

    // (d) 활동 기록

    @Test
    fun `활동 기록은 유효 참여자만 최신순으로 조회한다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val participant = UUID.randomUUID()
        insertMember(groupId, participant, SearchGroupMemberStatus.ACTIVE)
        insertEvent(groupId, SearchGroupEventType.GROUP_OPENED, LocalDateTime.now().minusMinutes(10))
        insertEvent(groupId, SearchGroupEventType.MEMBER_JOINED, LocalDateTime.now().minusMinutes(1))

        val events = searchGroupService.listEvents(groupId, participant, size = 20, offset = 0)

        assertEquals(2, events.size)
        assertEquals(SearchGroupEventType.MEMBER_JOINED, events[0].type, "createdAt DESC, id DESC")
        assertEquals(SearchGroupEventType.GROUP_OPENED, events[1].type)

        val e =
            assertFailsWith<BusinessException> {
                searchGroupService.listEvents(groupId, UUID.randomUUID(), size = 20, offset = 0)
            }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
    }

    // --- 시드 helper (전부 커밋된 행) ---

    private fun insertPost(
        authorId: UUID,
        status: MissingAnimalStatus = MissingAnimalStatus.SEARCHING,
        deleted: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO post (id, author_id, author_name, title, phone_num, time, place, gender, gratuity,
                              description, lat, lng, open_chat_url, missing_animal_status, animal_type, breed_id,
                              created_at, updated_at, deleted_at)
            VALUES (?, ?, '보호자', '테스트 실종 소식', '010-0000-0000', NOW(6), '서울 강남구', '남아', 0,
                    '설명', 37.5, 127.0, NULL, ?, 'DOG', NULL, NOW(6), NOW(6), ?)
            """.trimIndent(),
            id.toString(),
            authorId.toString(),
            status.name,
            if (deleted) Timestamp.valueOf(LocalDateTime.now()) else null,
        )
        return id
    }

    private fun insertGroup(
        postId: UUID,
        joinPolicy: JoinPolicy = JoinPolicy.OPEN,
        status: SearchGroupStatus = SearchGroupStatus.ACTIVE,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO search_group (id, post_id, join_policy, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            postId.toString(),
            joinPolicy.name,
            status.name,
        )
        return id
    }

    private fun insertMember(
        groupId: UUID,
        userId: UUID,
        status: SearchGroupMemberStatus,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO search_group_member (id, group_id, user_id, user_name, status, created_at, updated_at)
            VALUES (?, ?, ?, NULL, ?, NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            groupId.toString(),
            userId.toString(),
            status.name,
        )
        return id
    }

    private fun insertBlock(
        groupId: UUID,
        userId: UUID,
        blockedBy: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO search_group_user_block (id, group_id, user_id, blocked_by, reason, blocked_at,
                                                 unblocked_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, NULL, NOW(6), NULL, NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            groupId.toString(),
            userId.toString(),
            blockedBy.toString(),
        )
        return id
    }

    private fun insertEvent(
        groupId: UUID,
        type: SearchGroupEventType,
        createdAt: LocalDateTime,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO search_group_event (id, group_id, type, actor_id, target_id, detail, created_at, updated_at)
            VALUES (?, ?, ?, NULL, NULL, NULL, ?, ?)
            """.trimIndent(),
            id.toString(),
            groupId.toString(),
            type.name,
            Timestamp.valueOf(createdAt),
            Timestamp.valueOf(createdAt),
        )
        return id
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
