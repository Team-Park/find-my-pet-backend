package com.park.animal.searchgroup

import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.NotificationService
import com.park.animal.notification.repository.NotificationRepository
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
import org.springframework.data.domain.PageRequest
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
import kotlin.test.assertTrue

/**
 * 직접 참여 lifecycle (설계 §8, §15, §16.1).
 * 커밋된 상태를 확인해야 하므로 테스트 트랜잭션 래핑을 끈다(NOT_SUPPORTED).
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
)
@Testcontainers
class SearchGroupMembershipIT {
    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var notificationRepository: NotificationRepository

    @Autowired lateinit var membershipService: SearchGroupMembershipService

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var ownerId: UUID
    private lateinit var joinerId: UUID
    private lateinit var postId: UUID
    private lateinit var groupId: UUID

    @BeforeEach
    fun seed() {
        listOf(
            "notification", "search_group_event", "search_group_user_block", "search_group_member",
            "search_group_team", "team_member", "team", "search_group", "sighting", "post_bookmark", "post",
        ).forEach { jdbcTemplate.execute("DELETE FROM $it") }

        ownerId = UUID.randomUUID()
        joinerId = UUID.randomUUID()
        postId = newPost(ownerId)
        groupId = newGroup(postId, JoinPolicy.OPEN)
    }

    private fun newPost(authorId: UUID): UUID =
        postRepository
            .save(
                Post(
                    authorId = authorId,
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

    private fun newGroup(
        forPostId: UUID,
        policy: JoinPolicy,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO search_group (id, post_id, join_policy, status, created_at, updated_at) VALUES (?,?,?,?,NOW(6),NOW(6))",
            id.toString(), forPostId.toString(), policy.name, "ACTIVE",
        )
        return id
    }

    private fun blockUser(
        forGroupId: UUID,
        userId: UUID,
    ) {
        jdbcTemplate.update(
            "INSERT INTO search_group_user_block (id, group_id, user_id, blocked_by, blocked_at, created_at, updated_at) " +
                "VALUES (?,?,?,?,NOW(6),NOW(6),NOW(6))",
            UUID.randomUUID().toString(), forGroupId.toString(), userId.toString(), ownerId.toString(),
        )
    }

    private fun memberRowCount(userId: UUID): Long =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM search_group_member WHERE group_id = ? AND user_id = ?",
            Long::class.java,
            groupId.toString(),
            userId.toString(),
        )!!

    private fun notificationCount(userId: UUID): Long =
        notificationRepository
            .findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 50))
            .totalElements

    @Test
    fun `OPEN 정책은 즉시 참여시키고 보호자에게 알림 1건을 남긴다`() {
        val res = membershipService.join(groupId, joinerId, "이참여")

        assertEquals(SearchGroupMemberStatus.ACTIVE, res.membership.status)
        assertTrue(!res.approvalPending)
        assertEquals(1L, notificationCount(ownerId))
        assertEquals(0L, notificationCount(joinerId))
    }

    @Test
    fun `APPROVAL_REQUIRED 는 신청 승인 거절 양측 알림을 남긴다`() {
        val approvalGroupId = newGroup(newPost(ownerId), JoinPolicy.APPROVAL_REQUIRED)

        val requested = membershipService.join(approvalGroupId, joinerId, "이참여")
        assertEquals(SearchGroupMemberStatus.PENDING, requested.membership.status)
        assertTrue(requested.approvalPending)
        assertEquals(1L, notificationCount(ownerId), "보호자에게 참여 신청 알림")

        val approved = membershipService.approve(approvalGroupId, requested.membership.membershipId, ownerId)
        assertEquals(SearchGroupMemberStatus.ACTIVE, approved.status)
        assertEquals(1L, notificationCount(joinerId), "신청자에게 승인 알림")

        val rejecterId = UUID.randomUUID()
        val pending = membershipService.join(approvalGroupId, rejecterId, "거절대상")
        val rejected = membershipService.reject(approvalGroupId, pending.membership.membershipId, ownerId)
        assertEquals(SearchGroupMemberStatus.REJECTED, rejected.status)
        assertEquals(1L, notificationCount(rejecterId), "신청자에게 거절 알림")
    }

    @Test
    fun `중복 클릭 3회에도 ACTIVE 행 1개 알림 1건만 남는다`() {
        val first = membershipService.join(groupId, joinerId, "이참여")
        val second = membershipService.join(groupId, joinerId, "이참여")
        val third = membershipService.join(groupId, joinerId, "이참여")

        assertEquals(first.membership.membershipId, second.membership.membershipId)
        assertEquals(first.membership.membershipId, third.membership.membershipId)
        assertEquals(1L, memberRowCount(joinerId))
        assertEquals(1L, notificationCount(ownerId))
    }

    @Test
    fun `LEFT 후 재가입은 새 행이 아니라 같은 행의 상태 전이다`() {
        val first = membershipService.join(groupId, joinerId, "이참여")
        membershipService.leaveMe(groupId, joinerId)
        val again = membershipService.join(groupId, joinerId, "이참여")

        assertEquals(first.membership.membershipId, again.membership.membershipId)
        assertEquals(SearchGroupMemberStatus.ACTIVE, again.membership.status)
        assertEquals(1L, memberRowCount(joinerId))
    }

    @Test
    fun `APPROVAL 에서 OPEN 으로 바뀐 뒤 PENDING 사용자가 다시 누르면 ACTIVE 가 된다`() {
        val gid = newGroup(newPost(ownerId), JoinPolicy.APPROVAL_REQUIRED)
        val pending = membershipService.join(gid, joinerId, "이참여")
        assertEquals(SearchGroupMemberStatus.PENDING, pending.membership.status)

        membershipService.updateJoinPolicy(gid, ownerId, JoinPolicy.OPEN)
        val activated = membershipService.join(gid, joinerId, "이참여")

        assertEquals(SearchGroupMemberStatus.ACTIVE, activated.membership.status)
        assertEquals(pending.membership.membershipId, activated.membership.membershipId)
    }

    @Test
    fun `정책 변경은 기존 ACTIVE 를 유지하고 PENDING 을 자동 승인하지 않는다`() {
        val gid = newGroup(newPost(ownerId), JoinPolicy.APPROVAL_REQUIRED)
        val pendingUser = UUID.randomUUID()
        val pending = membershipService.join(gid, pendingUser, "대기자")

        membershipService.updateJoinPolicy(gid, ownerId, JoinPolicy.OPEN)
        val activeUser = UUID.randomUUID()
        membershipService.join(gid, activeUser, "즉시참여")
        membershipService.updateJoinPolicy(gid, ownerId, JoinPolicy.APPROVAL_REQUIRED)

        val all = membershipService.list(gid, ownerId, null)
        assertEquals(
            SearchGroupMemberStatus.PENDING,
            all.first { it.membershipId == pending.membership.membershipId }.status,
        )
        assertEquals(
            SearchGroupMemberStatus.ACTIVE,
            all.first { it.userId == activeUser }.status,
        )
    }

    @Test
    fun `다른 그룹의 membershipId 로 승인하면 404 다 (IDOR)`() {
        val otherOwnerId = UUID.randomUUID()
        val otherGroupId = newGroup(newPost(otherOwnerId), JoinPolicy.APPROVAL_REQUIRED)
        val victim = membershipService.join(otherGroupId, joinerId, "이참여")

        val e =
            assertFailsWith<BusinessException> {
                membershipService.approve(groupId, victim.membership.membershipId, ownerId)
            }
        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP, e.errorCode)
    }

    @Test
    fun `차단된 사용자는 가입할 수 없다`() {
        blockUser(groupId, joinerId)

        val e = assertFailsWith<BusinessException> { membershipService.join(groupId, joinerId, "이참여") }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
        assertEquals(0L, memberRowCount(joinerId))
    }

    @Test
    fun `차단된 사용자는 개인 참여 종료도 403 이다`() {
        // 설계 §6.3 — 차단이 활성인 동안에는 모든 접근을 거부한다. 탈퇴도 예외가 아니다.
        membershipService.join(groupId, joinerId, "이참여")
        blockUser(groupId, joinerId)

        val e = assertFailsWith<BusinessException> { membershipService.leaveMe(groupId, joinerId) }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
    }

    @Test
    fun `팀 경유 사용자는 개인 참여 종료로 나갈 수 없다`() {
        val e = assertFailsWith<BusinessException> { membershipService.leaveMe(groupId, UUID.randomUUID()) }
        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP, e.errorCode)
    }

    @Test
    fun `이미 종료한 참여를 다시 종료하면 409 다`() {
        membershipService.join(groupId, joinerId, "이참여")
        membershipService.leaveMe(groupId, joinerId)

        val e = assertFailsWith<BusinessException> { membershipService.leaveMe(groupId, joinerId) }
        assertEquals(ErrorCode.SEARCH_GROUP_STATE_CONFLICT, e.errorCode)
    }

    @Test
    fun `보호자가 내보내면 REMOVED 가 되고 대상자에게 알림이 간다`() {
        val joined = membershipService.join(groupId, joinerId, "이참여")

        val removed = membershipService.remove(groupId, joined.membership.membershipId, ownerId)

        assertEquals(SearchGroupMemberStatus.REMOVED, removed.status)
        assertEquals(1L, notificationCount(joinerId))
    }

    @Test
    fun `참여자 목록에서 참여자는 ACTIVE 만 보고 보호자는 PENDING 까지 본다`() {
        val gid = newGroup(newPost(ownerId), JoinPolicy.APPROVAL_REQUIRED)
        val activeUser = UUID.randomUUID()
        val pendingUser = UUID.randomUUID()
        val active = membershipService.join(gid, activeUser, "참여자")
        membershipService.approve(gid, active.membership.membershipId, ownerId)
        membershipService.join(gid, pendingUser, "대기자")

        assertEquals(2, membershipService.list(gid, ownerId, null).size)
        assertEquals(1, membershipService.list(gid, activeUser, null).size)
        assertEquals(
            SearchGroupMemberStatus.ACTIVE,
            membershipService.list(gid, activeUser, SearchGroupMemberStatus.PENDING).single().status,
            "참여자가 status 파라미터로 PENDING 을 요청해도 ACTIVE 목록만 받는다",
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
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
