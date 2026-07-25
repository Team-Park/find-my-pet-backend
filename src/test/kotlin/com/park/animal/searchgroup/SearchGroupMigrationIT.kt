package com.park.animal.searchgroup

import com.park.animal.common.config.JpaConfig
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupMember
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import com.park.animal.team.entity.Team
import com.park.animal.team.entity.TeamMember
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.repository.TeamMemberRepository
import com.park.animal.team.repository.TeamRepository
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V12(함께 찾기 스키마) 마이그레이션 검증 — 운영과 동일한 mysql:8.4 + Flyway 전체 체인.
 *
 * 백필은 "V12 실행 시점의 post 스냅샷" 을 대상으로 하므로, 컨테이너에 V1~V11 만 먼저 적용하고
 * post 를 심은 뒤 V12 를 돌려야 검증이 성립한다. @DynamicPropertySource 는 컨테이너 기동 후,
 * ApplicationContext 생성 전에 정확히 한 번 실행되므로 그 안에서 V11 스테이징과 시딩을 끝낸다.
 * V12 는 일부러 적용하지 않고 애플리케이션 자신의 Flyway 오토컨피그가 올리게 둔다 —
 * 운영과 같은 경로로 검증되고, V12 가 깨지면 컨텍스트 로딩 실패로 즉시 드러난다.
 *
 * 트랜잭션 래핑을 끄는 이유(NOT_SUPPORTED, SearchFulltextIT 와 동일):
 * UNIQUE 제약 위반과 조건부 UPDATE 의 영향 행 수는 실제 커밋 경계에서만 관측 가능하다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaConfig::class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SearchGroupMigrationIT {
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var searchGroupRepository: SearchGroupRepository

    @Autowired lateinit var searchGroupMemberRepository: SearchGroupMemberRepository

    @Autowired lateinit var teamRepository: TeamRepository

    @Autowired lateinit var teamMemberRepository: TeamMemberRepository

    private fun count(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.javaObjectType) ?: -1L

    private fun collationOf(
        table: String,
        column: String,
    ): String? =
        jdbcTemplate.queryForObject(
            """
            SELECT COLLATION_NAME FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '$table' AND COLUMN_NAME = '$column'
            """.trimIndent(),
            String::class.java,
        )

    @Test
    @Order(1)
    fun `V1부터 V12까지 전체 체인이 깨끗한 컨테이너에서 통과한다`() {
        assertEquals(0L, count("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0"), "실패한 마이그레이션이 남아 있다")
        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '12' AND success = 1"),
            "V12 가 적용되지 않았다",
        )

        assertEquals(
            7L,
            count(
                """
                SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME IN ('search_group', 'search_group_member', 'search_group_user_block',
                                      'team', 'team_member', 'search_group_team', 'search_group_event')
                """.trimIndent(),
            ),
            "V12 가 만들어야 할 테이블 7개가 다 없다",
        )

        // BaseEntity 매핑상 deleted_at 이 없으면 모든 SELECT 가 Unknown column 으로 죽는다(F7).
        assertEquals(
            7L,
            count(
                """
                SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'deleted_at'
                   AND TABLE_NAME IN ('search_group', 'search_group_member', 'search_group_user_block',
                                      'team', 'team_member', 'search_group_team', 'search_group_event')
                """.trimIndent(),
            ),
            "신규 테이블에 deleted_at 이 빠졌다",
        )

        assertEquals(
            5L,
            count(
                """
                SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification'
                   AND COLUMN_NAME IN ('actor_user_id', 'actor_name', 'post_id', 'group_id', 'team_id')
                """.trimIndent(),
            ),
            "notification 구조화 컨텍스트 컬럼 5개가 없다",
        )
    }

    @Test
    @Order(2)
    fun `백필은 삭제되지 않은 SEARCHING 소식에만 OPEN ACTIVE 그룹을 하나씩 만든다`() {
        assertEquals(2L, count("SELECT COUNT(*) FROM search_group"), "백필 대상은 SEARCHING 2건뿐이어야 한다")

        listOf(POST_SEARCHING_A, POST_SEARCHING_B).forEach { postId ->
            assertEquals(
                1L,
                count("SELECT COUNT(*) FROM search_group WHERE post_id = '$postId'"),
                "SEARCHING 소식 $postId 에 그룹이 정확히 1개여야 한다",
            )
        }

        assertEquals(
            2L,
            count(
                """
                SELECT COUNT(*) FROM search_group
                 WHERE join_policy = 'OPEN' AND status = 'ACTIVE'
                   AND archived_reason IS NULL AND archived_at IS NULL AND deleted_at IS NULL
                """.trimIndent(),
            ),
            "백필 그룹은 OPEN/ACTIVE 이고 보관 필드가 비어 있어야 한다",
        )

        listOf(POST_SEEN, POST_FOUND, POST_SEARCHING_DELETED).forEach { postId ->
            assertEquals(
                0L,
                count("SELECT COUNT(*) FROM search_group WHERE post_id = '$postId'"),
                "SEEN / FOUND / soft-delete 는 백필 대상이 아니다 ($postId)",
            )
        }

        // 엔티티 매핑도 함께 확인 — 백필 행의 id 는 MySQL UUID() 가 만든 문자열이다.
        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(UUID.fromString(POST_SEARCHING_A))
        assertNotNull(group, "백필된 그룹을 엔티티로 읽을 수 있어야 한다")
        assertEquals(JoinPolicy.OPEN, group.joinPolicy)
        assertEquals(SearchGroupStatus.ACTIVE, group.status)
        assertNull(group.archivedReason)
    }

    @Test
    @Order(3)
    fun `uq_tm_single_active_leader 는 같은 팀의 두 번째 활성 팀장을 거부한다`() {
        val team =
            teamRepository.save(
                Team(name = "강남 수색팀", description = "테스트 팀", createdBy = UUID.randomUUID()),
            )

        val leader =
            teamMemberRepository.save(
                TeamMember(
                    teamId = team.id,
                    userId = UUID.randomUUID(),
                    userName = "팀장",
                    role = TeamRole.LEADER,
                    status = TeamMemberStatus.ACTIVE,
                    joinedAt = LocalDateTime.now(),
                ),
            )
        assertNotNull(leader.id)

        // active_leader_key(GENERATED STORED)가 엔티티에 매핑돼 있으면 여기서 1062 가 아니라
        // "value specified for generated column is not allowed"(3105) 로 실패한다.
        val duplicate =
            assertFailsWith<DataIntegrityViolationException> {
                teamMemberRepository.save(
                    TeamMember(
                        teamId = team.id,
                        userId = UUID.randomUUID(),
                        userName = "두번째 팀장",
                        role = TeamRole.LEADER,
                        status = TeamMemberStatus.ACTIVE,
                        joinedAt = LocalDateTime.now(),
                    ),
                )
            }
        assertTrue(
            duplicate.message?.contains("uq_tm_single_active_leader") == true,
            "uq_tm_single_active_leader 위반이어야 한다. 실제: ${duplicate.message}",
        )

        // 활성이 아닌 팀장 이력은 generated key 가 NULL 이라 충돌하지 않는다.
        val formerLeader =
            teamMemberRepository.save(
                TeamMember(
                    teamId = team.id,
                    userId = UUID.randomUUID(),
                    userName = "전 팀장",
                    role = TeamRole.LEADER,
                    status = TeamMemberStatus.LEFT,
                    joinedAt = LocalDateTime.now(),
                ),
            )
        assertNotNull(formerLeader.id)

        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM team_member WHERE team_id = '${team.id}' AND active_leader_key IS NOT NULL"),
            "활성 팀장 generated key 는 팀당 1개여야 한다",
        )
    }

    @Test
    @Order(4)
    fun `post id 와 search_group post_id 의 collation 이 일치한다`() {
        val postIdCollation = collationOf("post", "id")
        val fkCollation = collationOf("search_group", "post_id")

        assertNotNull(postIdCollation, "post.id collation 을 읽지 못했다")
        assertNotNull(fkCollation, "search_group.post_id collation 을 읽지 못했다")
        assertEquals(
            postIdCollation,
            fkCollation,
            "collation 이 다르면 fk_search_group_post 가 마이그레이션 타임에 터진다",
        )

        assertEquals(
            1L,
            count(
                """
                SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'search_group'
                   AND CONSTRAINT_NAME = 'fk_search_group_post' AND CONSTRAINT_TYPE = 'FOREIGN KEY'
                """.trimIndent(),
            ),
            "fk_search_group_post 가 생성되지 않았다",
        )
    }

    @Test
    @Order(5)
    fun `멤버십 재가입은 새 행을 만들지 않고 같은 행의 status 만 되돌린다`() {
        val group = searchGroupRepository.findByPostIdAndDeletedAtIsNull(UUID.fromString(POST_SEARCHING_B))!!
        val userId = UUID.randomUUID()

        val saved =
            searchGroupMemberRepository.save(
                SearchGroupMember(
                    groupId = group.id,
                    userId = userId,
                    userName = "참여자",
                    status = SearchGroupMemberStatus.ACTIVE,
                    joinedAt = LocalDateTime.now(),
                ),
            )
        val membershipId = saved.id

        // ACTIVE -> LEFT
        assertEquals(
            1,
            searchGroupMemberRepository.transition(
                membershipId = membershipId,
                groupId = group.id,
                expected = SearchGroupMemberStatus.ACTIVE,
                next = SearchGroupMemberStatus.LEFT,
                decidedBy = userId,
                occurredAt = LocalDateTime.now(),
            ),
        )

        // 같은 전이를 한 번 더 = 영향 행 0 (이미 다른 상태). 409 판정의 근거다.
        assertEquals(
            0,
            searchGroupMemberRepository.transition(
                membershipId = membershipId,
                groupId = group.id,
                expected = SearchGroupMemberStatus.ACTIVE,
                next = SearchGroupMemberStatus.LEFT,
                decidedBy = userId,
                occurredAt = LocalDateTime.now(),
            ),
        )

        // LEFT -> ACTIVE (재가입). @SQLDelete 가 없으므로 UNIQUE(group_id,user_id)와 충돌하지 않는다.
        assertEquals(
            1,
            searchGroupMemberRepository.activate(
                membershipId = membershipId,
                groupId = group.id,
                expected = SearchGroupMemberStatus.LEFT,
                decidedBy = userId,
                occurredAt = LocalDateTime.now(),
            ),
        )

        val reloaded = searchGroupMemberRepository.findByGroupIdAndUserId(group.id, userId)
        assertNotNull(reloaded)
        assertEquals(membershipId, reloaded.id, "재가입이 새 행을 만들면 안 된다 (F15)")
        assertEquals(SearchGroupMemberStatus.ACTIVE, reloaded.status)
        assertNull(reloaded.deletedAt, "멤버십 테이블은 deleted_at 을 절대 쓰지 않는다")

        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM search_group_member WHERE group_id = '${group.id}' AND user_id = '$userId'"),
            "같은 (group_id,user_id) 행은 언제나 1개여야 한다",
        )

        // 다른 그룹 id 로는 같은 멤버십을 건드릴 수 없어야 한다 (IDOR 방어, 설계 16.1).
        val otherGroup = searchGroupRepository.findByPostIdAndDeletedAtIsNull(UUID.fromString(POST_SEARCHING_A))!!
        assertEquals(
            0,
            searchGroupMemberRepository.transition(
                membershipId = membershipId,
                groupId = otherGroup.id,
                expected = SearchGroupMemberStatus.ACTIVE,
                next = SearchGroupMemberStatus.REMOVED,
                decidedBy = UUID.randomUUID(),
                occurredAt = LocalDateTime.now(),
            ),
        )
        assertNull(searchGroupMemberRepository.findByIdAndGroupId(membershipId, otherGroup.id))
    }

    companion object {
        private const val POST_SEARCHING_A = "aaaaaaaa-0000-4000-8000-000000000001"
        private const val POST_SEARCHING_B = "aaaaaaaa-0000-4000-8000-000000000002"
        private const val POST_SEEN = "bbbbbbbb-0000-4000-8000-000000000001"
        private const val POST_FOUND = "cccccccc-0000-4000-8000-000000000001"
        private const val POST_SEARCHING_DELETED = "dddddddd-0000-4000-8000-000000000001"
        private const val OWNER_ID = "eeeeeeee-0000-4000-8000-000000000001"

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
            stageUpToV11AndSeedPosts()

            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            // 운영 마이그레이션 체인(V2 빈 파일 = legacy 스키마) 보완: 테스트 전용 V2.1 베이스라인 추가
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }

        /**
         * V1~V11 만 적용하고 백필 대상/비대상 post 를 심는다. V12 는 애플리케이션 Flyway 가 올린다.
         * @DynamicPropertySource 는 컨테이너 기동 후 · DataSource 생성 전에 호출되므로 여기가 유일한 자리다.
         */
        private fun stageUpToV11AndSeedPosts() {
            val dataSource = DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password)

            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/testsupport")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target(MigrationVersion.fromVersion("11"))
                .load()
                .migrate()

            val jdbc = JdbcTemplate(dataSource)
            insertPost(jdbc, POST_SEARCHING_A, "수색중 A", "SEARCHING", softDeleted = false)
            insertPost(jdbc, POST_SEARCHING_B, "수색중 B", "SEARCHING", softDeleted = false)
            insertPost(jdbc, POST_SEEN, "목격됨", "SEEN", softDeleted = false)
            insertPost(jdbc, POST_FOUND, "찾음", "FOUND", softDeleted = false)
            insertPost(jdbc, POST_SEARCHING_DELETED, "삭제된 수색중", "SEARCHING", softDeleted = true)
        }

        private fun insertPost(
            jdbc: JdbcTemplate,
            id: String,
            title: String,
            status: String,
            softDeleted: Boolean,
        ) {
            val deletedAt = if (softDeleted) "NOW(6)" else "NULL"
            jdbc.update(
                """
                INSERT INTO post (id, author_id, author_name, title, phone_num, time, place, gender, gratuity,
                                  description, lat, lng, open_chat_url, missing_animal_status, animal_type,
                                  created_at, updated_at, deleted_at)
                VALUES (?, ?, '보호자', ?, '010-0000-0000', NOW(6), '서울 강남구 역삼동', '남아', 0,
                        '백필 검증용 시드 데이터', 37.5012, 127.0396, NULL, ?, 'DOG', NOW(6), NOW(6), $deletedAt)
                """.trimIndent(),
                id,
                OWNER_ID,
                title,
                status,
            )
        }
    }
}
