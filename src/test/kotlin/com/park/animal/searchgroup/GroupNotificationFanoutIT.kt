package com.park.animal.searchgroup

import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.JpaConfig
import com.park.animal.notification.NotificationService
import com.park.animal.notification.entity.NotificationType
import com.park.animal.notification.repository.NotificationRepository
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
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

/**
 * 설계 §9 "여러 경로로 같은 그룹 권한을 가진 사용자는 알림을 한 번만 받는다" 를 고정한다.
 * 직접 ACTIVE 멤버이면서 동시에 지원 팀의 ACTIVE 팀원인 사용자가 대상이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    SearchGroupTestMetricsConfig::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    NotificationService::class,
    GroupNotificationPublisher::class,
)
@Testcontainers
class GroupNotificationFanoutIT {
    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var notificationRepository: NotificationRepository

    @Autowired lateinit var publisher: GroupNotificationPublisher

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var postId: UUID
    private lateinit var groupId: UUID
    private lateinit var ownerId: UUID
    private lateinit var dualUserId: UUID
    private lateinit var teamOnlyUserId: UUID

    @BeforeEach
    fun seed() {
        listOf(
            "notification", "search_group_event", "search_group_user_block", "search_group_member",
            "search_group_team", "team_member", "team", "search_group", "sighting", "post_bookmark", "post",
        ).forEach { jdbcTemplate.execute("DELETE FROM $it") }

        ownerId = UUID.randomUUID()
        dualUserId = UUID.randomUUID()
        teamOnlyUserId = UUID.randomUUID()

        val post =
            postRepository.save(
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
            )
        postId = post.id
        groupId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO search_group (id, post_id, join_policy, status, created_at, updated_at) VALUES (?,?,?,?,NOW(6),NOW(6))",
            groupId.toString(), postId.toString(), "OPEN", "ACTIVE",
        )
        jdbcTemplate.update(
            "INSERT INTO search_group_member (id, group_id, user_id, user_name, status, joined_at, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,NOW(6),NOW(6),NOW(6))",
            UUID.randomUUID().toString(), groupId.toString(), dualUserId.toString(), "이중경로", "ACTIVE",
        )

        val teamId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO team (id, name, status, created_by, created_at, updated_at) VALUES (?,?,?,?,NOW(6),NOW(6))",
            teamId.toString(), "강남수색팀", "ACTIVE", teamOnlyUserId.toString(),
        )
        listOf(teamOnlyUserId to "LEADER", dualUserId to "MEMBER").forEach { (uid, role) ->
            jdbcTemplate.update(
                "INSERT INTO team_member (id, team_id, user_id, user_name, role, status, joined_at, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,NOW(6),NOW(6),NOW(6))",
                UUID.randomUUID().toString(), teamId.toString(), uid.toString(), "팀원", role, "ACTIVE",
            )
        }
        jdbcTemplate.update(
            "INSERT INTO search_group_team (id, group_id, team_id, status, requested_by, requested_at, activated_at, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,NOW(6),NOW(6),NOW(6),NOW(6))",
            UUID.randomUUID().toString(), groupId.toString(), teamId.toString(), "ACTIVE", ownerId.toString(),
        )
    }

    private fun countFor(userId: UUID): Long =
        notificationRepository
            .findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 50))
            .totalElements

    @Test
    fun `직접과 팀 두 경로를 모두 가진 사용자도 알림을 한 번만 받는다`() {
        publisher.notifyGroup(
            groupId = groupId,
            postId = postId,
            type = NotificationType.SEARCH_ENDED,
            excluding = setOf(ownerId),
            actorUserId = ownerId,
            body = null,
        )

        assertEquals(1L, countFor(dualUserId), "직접 ACTIVE + 팀 ACTIVE 인 사용자는 1건만 받아야 한다")
        assertEquals(1L, countFor(teamOnlyUserId))
        assertEquals(0L, countFor(ownerId), "excluding 에 담긴 보호자는 받지 않는다")
    }

    @Test
    fun `구조화 컨텍스트가 알림 행에 저장된다`() {
        publisher.notifyGroup(
            groupId = groupId,
            postId = postId,
            type = NotificationType.SEARCH_ENDED,
            excluding = setOf(ownerId),
            actorUserId = ownerId,
            body = null,
        )

        val row =
            notificationRepository
                .findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(dualUserId, PageRequest.of(0, 1))
                .content
                .first()
        assertEquals(groupId, row.groupId)
        assertEquals(postId, row.postId)
        assertEquals(ownerId, row.actorUserId)
        assertEquals("/lost/$postId/group", row.link)
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
