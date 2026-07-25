package com.park.animal.searchgroup

import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.NotificationService
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
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
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 보호자 차단 (설계 §6.3, §14.3, §16.5).
 * 차단은 허용 권한보다 우선하며, 팀을 통해 얻은 파생 권한에도 그대로 적용된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    SearchGroupTestMetricsConfig::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchGroupEventRecorder::class,
    NotificationService::class,
    GroupNotificationPublisher::class,
    SearchGroupMembershipService::class,
    SearchGroupBlockService::class,
)
@Testcontainers
class SearchGroupBlockIT {
    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var blockService: SearchGroupBlockService

    @Autowired lateinit var membershipService: SearchGroupMembershipService

    @Autowired lateinit var accessResolver: SearchGroupAccessResolver

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var ownerId: UUID
    private lateinit var postId: UUID
    private lateinit var groupId: UUID

    @BeforeEach
    fun seed() {
        listOf(
            "notification", "search_group_event", "search_group_user_block", "search_group_member",
            "search_group_team", "team_member", "team", "search_group", "sighting", "post_bookmark", "post",
        ).forEach { jdbcTemplate.execute("DELETE FROM $it") }

        ownerId = UUID.randomUUID()
        postId =
            postRepository
                .save(
                    Post(
                        authorId = ownerId,
                        authorName = "보호자",
                        title = "말티즈를 찾습니다",
                        phoneNum = "010-0000-0000",
                        time = LocalDateTime.now(),
                        place = "서울 강남구 역삼동",
                        gender = "남아",
                        gratuity = 0,
                        description = "겁이 많아요.",
                        lat = 37.5012,
                        lng = 127.0396,
                        openChatUrl = null,
                        missingAnimalStatus = MissingAnimalStatus.SEARCHING,
                        animalType = AnimalType.DOG,
                    ),
                ).id
        groupId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO search_group (id, post_id, join_policy, status, created_at, updated_at) VALUES (?,?,?,?,NOW(6),NOW(6))",
            groupId.toString(), postId.toString(), JoinPolicy.OPEN.name, "ACTIVE",
        )
    }

    private fun seedSupportingTeam(memberIds: List<UUID>): UUID {
        val teamId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO team (id, name, status, created_by, created_at, updated_at) VALUES (?,?,?,?,NOW(6),NOW(6))",
            teamId.toString(), "강남수색팀", "ACTIVE", memberIds.first().toString(),
        )
        memberIds.forEachIndexed { idx, uid ->
            jdbcTemplate.update(
                "INSERT INTO team_member (id, team_id, user_id, user_name, role, status, joined_at, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,NOW(6),NOW(6),NOW(6))",
                UUID.randomUUID().toString(), teamId.toString(), uid.toString(), "팀원",
                if (idx == 0) "LEADER" else "MEMBER", "ACTIVE",
            )
        }
        jdbcTemplate.update(
            "INSERT INTO search_group_team (id, group_id, team_id, status, requested_by, requested_at, activated_at, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,NOW(6),NOW(6),NOW(6),NOW(6))",
            UUID.randomUUID().toString(), groupId.toString(), teamId.toString(), "ACTIVE", ownerId.toString(),
        )
        return teamId
    }

    @Test
    fun `팀 경유 팀원 한 명을 차단해도 나머지 팀 지원은 유지된다`() {
        val leaderId = UUID.randomUUID()
        val blockedMemberId = UUID.randomUUID()
        val teamId = seedSupportingTeam(listOf(leaderId, blockedMemberId))

        blockService.block(groupId, blockedMemberId, ownerId, "반복 신고")

        val effective = accessResolver.effectiveMemberIds(groupId)
        assertTrue(leaderId in effective, "차단되지 않은 팀원의 파생 권한은 유지된다")
        assertFalse(blockedMemberId in effective, "차단은 팀 파생 권한보다 우선한다")
        assertFalse(accessResolver.resolve(groupId, blockedMemberId)!!.canRead)

        val supportStatus =
            jdbcTemplate.queryForObject(
                "SELECT status FROM search_group_team WHERE group_id = ? AND team_id = ?",
                String::class.java,
                groupId.toString(),
                teamId.toString(),
            )
        assertEquals("ACTIVE", supportStatus, "팀 지원 연결 자체는 그대로다")
    }

    @Test
    fun `차단된 사용자의 접근 정보는 비차단 비참여자와 blocked 외에는 동일하다`() {
        val blockedId = UUID.randomUUID()
        val strangerId = UUID.randomUUID()
        blockService.block(groupId, blockedId, ownerId, "반복 신고")

        val blocked = accessResolver.resolve(groupId, blockedId)!!
        val stranger = accessResolver.resolve(groupId, strangerId)!!

        assertEquals(
            stranger.copy(viewerId = null),
            blocked.copy(viewerId = null, blocked = false),
            "blocked 플래그를 제외한 모든 필드가 같아야 DTO 로 차단 사실이 새지 않는다",
        )
        assertFalse(blocked.canJoin)
        val e = assertFailsWith<BusinessException> { membershipService.join(groupId, blockedId, "차단대상") }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode, "권한 없음과 같은 코드라 차단 여부를 구분할 수 없다")
    }

    @Test
    fun `보호자 자신은 차단 대상이 될 수 없다`() {
        val e = assertFailsWith<BusinessException> { blockService.block(groupId, ownerId, ownerId, null) }
        assertEquals(ErrorCode.SEARCH_GROUP_STATE_CONFLICT, e.errorCode)
    }

    @Test
    fun `차단하면 대상의 직접 참여도 함께 종료되고 목록에 표시 이름이 남는다`() {
        val memberId = UUID.randomUUID()
        val joined = membershipService.join(groupId, memberId, "참여자")

        blockService.block(groupId, memberId, ownerId, "반복 신고")

        val status =
            jdbcTemplate.queryForObject(
                "SELECT status FROM search_group_member WHERE id = ?",
                String::class.java,
                joined.membership.membershipId.toString(),
            )
        assertEquals(SearchGroupMemberStatus.REMOVED.name, status)
        assertEquals(
            "참여자",
            blockService.listBlocks(groupId, ownerId).single().userName,
            "보호자 화면이 사용자 id 만 보여주지 않도록 멤버십 행의 표시 이름을 함께 싣는다",
        )
    }

    @Test
    fun `차단 후 해제하면 다시 참여할 수 있고 같은 멤버십 행을 쓴다`() {
        val memberId = UUID.randomUUID()
        val first = membershipService.join(groupId, memberId, "참여자")
        blockService.block(groupId, memberId, ownerId, "반복 신고")

        blockService.unblock(groupId, memberId, ownerId)
        val again = membershipService.join(groupId, memberId, "참여자")

        assertEquals(first.membership.membershipId, again.membership.membershipId)
        assertEquals(SearchGroupMemberStatus.ACTIVE, again.membership.status)
        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_group_user_block WHERE group_id = ? AND user_id = ?",
                Long::class.java,
                groupId.toString(),
                memberId.toString(),
            ),
            "차단 행은 재사용된다 (UNIQUE 충돌을 만드는 새 INSERT 금지)",
        )
    }

    @Test
    fun `차단 재적용은 새 행을 만들지 않고 같은 행을 되살린다`() {
        val memberId = UUID.randomUUID()
        blockService.block(groupId, memberId, ownerId, "1차")
        blockService.unblock(groupId, memberId, ownerId)
        val reblocked = blockService.block(groupId, memberId, ownerId, "2차")

        assertEquals(1, blockService.listBlocks(groupId, ownerId).size)
        assertEquals("2차", reblocked.reason)
    }

    @Test
    fun `차단 목록은 보호자만 볼 수 있고 사유는 여기서만 노출된다`() {
        val memberId = UUID.randomUUID()
        val outsiderId = UUID.randomUUID()
        blockService.block(groupId, memberId, ownerId, "반복 신고")

        val blocks = blockService.listBlocks(groupId, ownerId)
        assertEquals(1, blocks.size)
        assertEquals("반복 신고", blocks.single().reason)

        val e = assertFailsWith<BusinessException> { blockService.listBlocks(groupId, outsiderId) }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
    }

    @Test
    fun `해제는 멱등하다`() {
        val memberId = UUID.randomUUID()
        blockService.block(groupId, memberId, ownerId, "반복 신고")

        blockService.unblock(groupId, memberId, ownerId)
        blockService.unblock(groupId, memberId, ownerId)
        blockService.unblock(groupId, UUID.randomUUID(), ownerId)

        assertEquals(0, blockService.listBlocks(groupId, ownerId).size)
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
