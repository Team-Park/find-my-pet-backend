package com.park.animal.searchgroup

import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.access.AccessSource
import com.park.animal.searchgroup.access.GroupRole
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupMember
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.persistence.EntityManager
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `@DataJpaTest` 는 actuator auto-configuration 을 포함하지 않으므로 MeterRegistry 를 직접 공급한다.
 * SimpleMeterRegistry 는 in-memory 라 테스트에서 카운터 값을 그대로 읽을 수 있다.
 */
@TestConfiguration
class AccessMetricsTestConfig {
    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}

/**
 * 수색그룹 접근 판정 고정 테스트 — 설계 §6.6 Effective Membership / §7 권한 규칙 / §20 관측.
 *
 * 시드는 전부 JdbcTemplate 로 직접 INSERT 한다. 판정 로직이 native SQL 이므로
 * 엔티티 생성자 형태가 아니라 "테이블에 어떤 행이 있는가" 만이 입력이기 때문이다.
 * 커밋된 행만 native 쿼리에 보이므로 테스트 트랜잭션 래핑을 끈다(NOT_SUPPORTED).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    AccessMetricsTestConfig::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
)
@Testcontainers
class SearchGroupAccessResolverIT {
    @Autowired lateinit var resolver: SearchGroupAccessResolver

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var searchGroupMemberRepository: SearchGroupMemberRepository

    @Autowired lateinit var entityManager: EntityManager

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Autowired lateinit var meterRegistry: MeterRegistry

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
            "post_image",
            "post",
        ).forEach { jdbcTemplate.update("DELETE FROM $it") }
    }

    @Test
    fun `보호자는 OWNER 이고 관리 권한을 가진다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)

        val access = resolver.resolve(groupId, owner)!!

        assertEquals(GroupRole.OWNER, access.role)
        assertEquals(setOf(AccessSource.OWNER), access.sources)
        assertEquals(owner, access.ownerUserId)
        assertTrue(access.isOwner)
        assertTrue(access.canRead)
        assertTrue(access.canWrite)
        assertTrue(access.canManage)
        assertFalse(access.canJoin, "이미 보호자이므로 참여 CTA 를 노출하지 않는다")
    }

    @Test
    fun `ACTIVE 직접 멤버는 PARTICIPANT 이고 DIRECT 출처를 가진다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val user = UUID.randomUUID()
        val membershipId = insertMember(groupId, user, SearchGroupMemberStatus.ACTIVE)

        val access = resolver.resolve(groupId, user)!!

        assertEquals(GroupRole.PARTICIPANT, access.role)
        assertEquals(setOf(AccessSource.DIRECT), access.sources)
        assertEquals(membershipId, access.directMembershipId)
        assertEquals(SearchGroupMemberStatus.ACTIVE, access.directMembershipStatus)
        assertEquals(0, access.teamCount)
        assertTrue(access.canRead)
        assertTrue(access.canWrite)
        assertFalse(access.canManage)
        assertFalse(access.canJoin)
    }

    @Test
    fun `PENDING 직접 멤버는 아직 참여자가 아니다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId, joinPolicy = JoinPolicy.APPROVAL_REQUIRED)
        val user = UUID.randomUUID()
        insertMember(groupId, user, SearchGroupMemberStatus.PENDING)

        val access = resolver.resolve(groupId, user)!!

        assertEquals(GroupRole.NONE, access.role)
        assertTrue(access.sources.isEmpty())
        assertEquals(SearchGroupMemberStatus.PENDING, access.directMembershipStatus)
        assertFalse(access.canRead)
        assertTrue(access.canJoin, "설계 §8.3 — 대기 사용자는 다시 참여를 누를 수 있어야 한다")
    }

    @Test
    fun `ACTIVE 팀 지원의 ACTIVE 팀원은 TEAM 출처로 참여자가 된다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val teamId = insertTeam(UUID.randomUUID())
        val member = UUID.randomUUID()
        insertTeamMember(teamId, member, TeamRole.MEMBER, TeamMemberStatus.ACTIVE)
        insertTeamSupport(groupId, teamId, SearchGroupTeamStatus.ACTIVE, owner)

        val access = resolver.resolve(groupId, member)!!

        assertEquals(GroupRole.PARTICIPANT, access.role)
        assertEquals(setOf(AccessSource.TEAM), access.sources)
        assertEquals(1, access.teamCount)
        assertNull(access.directMembershipId)
        assertTrue(access.canWrite)
    }

    @Test
    fun `팀 탈퇴 직후 파생 권한이 사라진다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val teamId = insertTeam(UUID.randomUUID())
        val member = UUID.randomUUID()
        insertTeamMember(teamId, member, TeamRole.MEMBER, TeamMemberStatus.LEFT)
        insertTeamSupport(groupId, teamId, SearchGroupTeamStatus.ACTIVE, owner)

        val access = resolver.resolve(groupId, member)!!

        assertEquals(GroupRole.NONE, access.role)
        assertEquals(0, access.teamCount)
        assertFalse(access.canRead)
    }

    @Test
    fun `팀 지원 종료 직후 파생 권한이 사라진다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val teamId = insertTeam(UUID.randomUUID())
        val member = UUID.randomUUID()
        insertTeamMember(teamId, member, TeamRole.MEMBER, TeamMemberStatus.ACTIVE)
        insertTeamSupport(groupId, teamId, SearchGroupTeamStatus.WITHDRAWN, owner)

        val access = resolver.resolve(groupId, member)!!

        assertEquals(GroupRole.NONE, access.role)
        assertEquals(0, access.teamCount)
        assertFalse(access.canRead)
    }

    @Test
    fun `차단된 팀원은 차단과 비참여를 구분할 수 없는 403 을 받는다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val teamId = insertTeam(UUID.randomUUID())
        val member = UUID.randomUUID()
        insertTeamMember(teamId, member, TeamRole.MEMBER, TeamMemberStatus.ACTIVE)
        insertTeamSupport(groupId, teamId, SearchGroupTeamStatus.ACTIVE, owner)
        insertBlock(groupId, member, owner)

        val access = resolver.resolve(groupId, member)!!
        assertTrue(access.blocked)
        assertEquals(GroupRole.PARTICIPANT, access.role, "차단은 role 이 아니라 접근 게이트로 표현한다")
        assertFalse(access.canRead)
        assertFalse(access.canJoin)

        val e = assertFailsWith<BusinessException> { resolver.requireRead(groupId, member) }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
    }

    @Test
    fun `차단 해제된 사용자는 다시 참여자로 복귀한다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val user = UUID.randomUUID()
        insertMember(groupId, user, SearchGroupMemberStatus.ACTIVE)
        insertBlock(groupId, user, owner, unblocked = true)

        val access = resolver.resolve(groupId, user)!!

        assertFalse(access.blocked)
        assertTrue(access.canRead)
    }

    @Test
    fun `soft-delete 된 post 의 그룹은 보이지 않는다 - 404`() {
        val postId = insertPost(owner, deleted = true)
        val groupId = insertGroup(postId)
        val user = UUID.randomUUID()
        insertMember(groupId, user, SearchGroupMemberStatus.ACTIVE)

        val access = resolver.resolve(groupId, user)!!
        assertTrue(access.postDeleted)
        assertFalse(access.visible)
        assertFalse(access.canRead)

        val e = assertFailsWith<BusinessException> { resolver.requireVisible(groupId, user) }
        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP, e.errorCode)
    }

    @Test
    fun `ARCHIVED 그룹은 읽기만 되고 쓰기는 410`() {
        val postId = insertPost(owner, status = MissingAnimalStatus.FOUND)
        val groupId = insertGroup(postId, status = SearchGroupStatus.ARCHIVED)
        val user = UUID.randomUUID()
        insertMember(groupId, user, SearchGroupMemberStatus.ACTIVE)

        val access = resolver.resolve(groupId, user)!!
        assertTrue(access.canRead)
        assertFalse(access.canWrite)
        assertFalse(access.canJoin)

        resolver.requireRead(groupId, user)
        val write = assertFailsWith<BusinessException> { resolver.requireWrite(groupId, user) }
        assertEquals(ErrorCode.SEARCH_ALREADY_ENDED, write.errorCode)

        val manage = assertFailsWith<BusinessException> { resolver.requireOwner(groupId, owner) }
        assertEquals(ErrorCode.SEARCH_ALREADY_ENDED, manage.errorCode)
    }

    @Test
    fun `보호자가 아닌 사용자의 requireOwner 는 403`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val user = UUID.randomUUID()
        insertMember(groupId, user, SearchGroupMemberStatus.ACTIVE)

        val e = assertFailsWith<BusinessException> { resolver.requireOwner(groupId, user) }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e.errorCode)
    }

    @Test
    fun `비로그인 조회는 역할 없이 그룹 요약만 얻는다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        insertMember(groupId, UUID.randomUUID(), SearchGroupMemberStatus.ACTIVE)

        val access = resolver.resolveByPostId(postId, null)!!

        assertEquals(groupId, access.groupId)
        assertEquals(postId, access.postId)
        assertNull(access.viewerId)
        assertEquals(GroupRole.NONE, access.role)
        assertTrue(access.sources.isEmpty())
        assertNull(access.directMembershipId)
        assertEquals(0, access.teamCount)
        assertFalse(access.blocked, "zero-UUID 바인딩이 다른 사용자의 차단 행을 잡아오면 안 된다")
        assertTrue(access.canJoin, "공개 CTA 는 로그인 유도를 위해 참여 가능으로 노출한다")
    }

    @Test
    fun `존재하지 않는 그룹은 null 이고 requireVisible 은 404`() {
        val missing = UUID.randomUUID()

        assertNull(resolver.resolve(missing, owner))
        val e = assertFailsWith<BusinessException> { resolver.requireVisible(missing, owner) }
        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP, e.errorCode)
    }

    @Test
    fun `접근 거절은 reason 태그만 가진 카운터로 집계된다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val stranger = UUID.randomUUID()

        val archivedPostId = insertPost(owner, status = MissingAnimalStatus.FOUND)
        val archivedGroupId = insertGroup(archivedPostId, status = SearchGroupStatus.ARCHIVED)

        // 컨텍스트가 클래스 단위로 재사용되고 테스트 순서가 보장되지 않으므로 절대값이 아니라 증분을 본다.
        val beforeNotFound = deniedCount("not_found")
        val beforeForbidden = deniedCount("forbidden")
        val beforeArchived = deniedCount("archived")

        assertFailsWith<BusinessException> { resolver.requireVisible(UUID.randomUUID(), owner) }
        assertFailsWith<BusinessException> { resolver.requireRead(groupId, stranger) }
        assertFailsWith<BusinessException> { resolver.requireOwner(archivedGroupId, owner) }

        assertEquals(1.0, deniedCount("not_found") - beforeNotFound)
        assertEquals(1.0, deniedCount("forbidden") - beforeForbidden)
        assertEquals(1.0, deniedCount("archived") - beforeArchived)

        val tagKeys =
            meterRegistry
                .find("fmp.searchgroup.access.denied")
                .counters()
                .flatMap { counter -> counter.id.tags.map { it.key } }
                .toSet()
        assertEquals(setOf("reason"), tagKeys, "설계 §20 — id 값을 메트릭 label 에 넣지 않는다")
    }

    @Test
    fun `effectiveMemberIds 는 보호자와 직접 팀 경로를 중복 없이 합치고 차단을 뺀다`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)

        val direct = UUID.randomUUID()
        val both = UUID.randomUUID() // 직접 참여 + 팀 경유 동시
        val teamOnly = UUID.randomUUID()
        val blocked = UUID.randomUUID()
        val pending = UUID.randomUUID()
        val leftTeamMember = UUID.randomUUID()

        insertMember(groupId, direct, SearchGroupMemberStatus.ACTIVE)
        insertMember(groupId, both, SearchGroupMemberStatus.ACTIVE)
        insertMember(groupId, pending, SearchGroupMemberStatus.PENDING)

        val teamId = insertTeam(UUID.randomUUID())
        insertTeamMember(teamId, both, TeamRole.MEMBER, TeamMemberStatus.ACTIVE)
        insertTeamMember(teamId, teamOnly, TeamRole.LEADER, TeamMemberStatus.ACTIVE)
        insertTeamMember(teamId, blocked, TeamRole.MEMBER, TeamMemberStatus.ACTIVE)
        insertTeamMember(teamId, leftTeamMember, TeamRole.MEMBER, TeamMemberStatus.LEFT)
        insertTeamSupport(groupId, teamId, SearchGroupTeamStatus.ACTIVE, owner)
        insertBlock(groupId, blocked, owner)

        val ids = resolver.effectiveMemberIds(groupId)

        assertEquals(setOf(owner, direct, both, teamOnly), ids)
    }

    @Test
    fun `accessibleGroupIds 는 볼 수 있는 그룹만 돌려주고 삭제된 글은 제외한다`() {
        val user = UUID.randomUUID()

        val minePostId = insertPost(user)
        val mineGroupId = insertGroup(minePostId)

        val joinedPostId = insertPost(owner)
        val joinedGroupId = insertGroup(joinedPostId)
        insertMember(joinedGroupId, user, SearchGroupMemberStatus.ACTIVE)

        val deletedPostId = insertPost(owner, deleted = true)
        val deletedGroupId = insertGroup(deletedPostId)
        insertMember(deletedGroupId, user, SearchGroupMemberStatus.ACTIVE)

        val blockedPostId = insertPost(owner)
        val blockedGroupId = insertGroup(blockedPostId)
        insertMember(blockedGroupId, user, SearchGroupMemberStatus.ACTIVE)
        insertBlock(blockedGroupId, user, owner)

        val strangerPostId = insertPost(owner)
        insertGroup(strangerPostId)

        val ids = resolver.accessibleGroupIds(user).toSet()

        assertEquals(setOf(mineGroupId, joinedGroupId), ids)
    }

    @Test
    fun `native 접근 판정은 Hibernate auto-flush 를 트리거하지 않는다 - resolve 먼저 mutate 나중`() {
        val postId = insertPost(owner)
        val groupId = insertGroup(postId)
        val joiner = UUID.randomUUID()

        TransactionTemplate(transactionManager).execute {
            searchGroupMemberRepository.save(
                SearchGroupMember(
                    groupId = groupId,
                    userId = joiner,
                    userName = null,
                    status = SearchGroupMemberStatus.ACTIVE,
                    joinedAt = LocalDateTime.now(),
                    requestedAt = null,
                ),
            )

            val beforeFlush = resolver.resolve(groupId, joiner)!!
            assertEquals(
                GroupRole.NONE,
                beforeFlush.role,
                "flush 전 native 쿼리는 새 멤버십을 보지 못한다 — 서비스는 resolve 를 먼저 하고 mutate 를 나중에 해야 한다",
            )

            entityManager.flush()

            val afterFlush = resolver.resolve(groupId, joiner)!!
            assertEquals(GroupRole.PARTICIPANT, afterFlush.role, "flush 후에는 같은 커넥션에서 보인다")
        }
    }

    // --- 메트릭 helper ---

    private fun deniedCount(reason: String): Double =
        meterRegistry
            .find("fmp.searchgroup.access.denied")
            .tag("reason", reason)
            .counter()
            ?.count()
            ?: 0.0

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

    private fun insertTeam(createdBy: UUID): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO team (id, name, description, status, created_by, created_at, updated_at)
            VALUES (?, '테스트 팀', NULL, 'ACTIVE', ?, NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            createdBy.toString(),
        )
        return id
    }

    private fun insertTeamMember(
        teamId: UUID,
        userId: UUID,
        role: TeamRole,
        status: TeamMemberStatus,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO team_member (id, team_id, user_id, user_name, role, status, created_at, updated_at)
            VALUES (?, ?, ?, NULL, ?, ?, NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            teamId.toString(),
            userId.toString(),
            role.name,
            status.name,
        )
        return id
    }

    private fun insertTeamSupport(
        groupId: UUID,
        teamId: UUID,
        status: SearchGroupTeamStatus,
        requestedBy: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO search_group_team (id, group_id, team_id, status, requested_by, requested_at,
                                           created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            groupId.toString(),
            teamId.toString(),
            status.name,
            requestedBy.toString(),
        )
        return id
    }

    private fun insertBlock(
        groupId: UUID,
        userId: UUID,
        blockedBy: UUID,
        unblocked: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO search_group_user_block (id, group_id, user_id, blocked_by, reason, blocked_at,
                                                 unblocked_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, NULL, NOW(6), ?, NOW(6), NOW(6))
            """.trimIndent(),
            id.toString(),
            groupId.toString(),
            userId.toString(),
            blockedBy.toString(),
            if (unblocked) Timestamp.valueOf(LocalDateTime.now()) else null,
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
            // 운영 체인의 V2 는 빈 파일이라 fresh DB 에서는 테스트 전용 V2.1 베이스라인이 필요하다.
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
