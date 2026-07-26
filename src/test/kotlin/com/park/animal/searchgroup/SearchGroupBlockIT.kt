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
import com.park.animal.searchgroup.repository.SearchGroupUserBlockRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
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
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @Autowired lateinit var blockRepository: SearchGroupUserBlockRepository

    @Autowired lateinit var transactionManager: PlatformTransactionManager

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

    /**
     * 해제 후 재차단 경합 (설계 §16.1 REPEATABLE READ 트랩 회귀 방지).
     *
     * `reactivate()` 와 `updateReasonOnly()` 가 상호작용하는 경로를 직접 겨냥한다 — 승자는 원시
     * 리포지토리로 커밋 시점을 정확히 통제하고(멤버십 IT 의 정책 변경/승인 경합 테스트와 같은 기법),
     * 패자는 실제 `blockService.block(...)` 을 부른다. 패자의 `reactivate()` 시도는 승자가 이미
     * 커밋한 `unblockedAt = null` 을 최신 커밋 값(current read)으로 다시 확인해 0행을 반환해야
     * 한다 — 이것이 "confirm 경로" 다. 이 클래스는 정책/승인 경합 테스트처럼 별도의
     * "목표값 -> 목표값" 확인용 UPDATE 를 추가로 두지 않는다(§ [SearchGroupBlockService] KDoc) —
     * `reactivate()` 자신의 WHERE 절이 시도이자 확인이다. 그래서 이 테스트가 증명해야 하는 것은
     * "패자가 그 경로를 실제로 탔는가" 이지, "확인용 UPDATE 가 타임스탬프를 다시 찍었는가" 가 아니다.
     *
     * 증명 방법: 승자의 `reactivate()` 가 쓴 `blocked_at` 값을 승자 트랜잭션 안에서(자신의 쓰기이므로
     * 커밋 전에도 read-your-own-writes 로 보인다) 캡처해 두고, 최종 커밋된 `blocked_at` 이 그 값과
     * **정확히 같음**을 확인한다. 만약 패자가 confirm 이 아니라 "직접 이겨서" 자신의 `reactivate()`
     * 호출로 다시 썼다면(버그: WHERE 절이 스냅샷 기준으로 판정돼 두 트랜잭션 모두 성공하는 경우)
     * `blocked_at` 은 패자의 (더 늦은) 호출 시각으로 바뀌었을 것이다. `reason` 은 반대로 패자의 값
     * ("2차")이어야 한다 — `updateReasonOnly` 는 `didBlock` 과 무관하게 항상 실행되기 때문이다
     * (§ [SearchGroupBlockService.block] 참고). 감사 기록·알림은 승자가 실제 서비스를 거치지 않았고
     * (원시 리포지토리 호출), 패자도 confirm 만 했으므로 총 0건이어야 한다 — 하나라도 있으면 패자가
     * `didBlock` 게이트 없이(혹은 그 게이트가 깨져) 무조건 발행했다는 뜻이다.
     *
     * **잔여 스케줄링 가정(결정론을 주장하지 않음)**: latch 는 "승자가 자신의 쓰기를 이미 마쳤다" 만
     * 보장한다. 그 뒤 패자가 자신의 읽기+락 대기 시도를 승자의 300ms 커밋 유예 안에 마치는지는
     * 스레드 스케줄러에 달려 있다 — 로컬 SQL 왕복 1회에 비해 넉넉한 여유이지만, 병적인 스케줄러
     * 기아 상태까지 수학적으로 배제하지는 못한다. (멤버십 IT 의 동일 기법 테스트들과 같은 가정.)
     */
    @Test
    fun `해제 후 동시 재차단 경합에서 패자는 확인만 하고 감사·알림을 중복 남기지 않는다`() {
        val memberId = UUID.randomUUID()
        blockService.block(groupId, memberId, ownerId, "1차")
        blockService.unblock(groupId, memberId, ownerId)
        val blockId = blockRepository.findByGroupIdAndUserId(groupId, memberId)!!.id
        // 최초 "1차" 차단이 이미 정당한 이벤트·알림 1건씩을 남겼다(멱등 게이트와 무관한 진짜 전이).
        // 그래서 아래 최종 단언은 절대값 0 이 아니라 "경합 전후로 늘지 않았다" 를 확인한다 — 그래야
        // 오직 이 경합에서 패자가 중복 발행했는지만 검증하고, 설정 단계의 정상 발행과 섞이지 않는다.
        val eventsBeforeRace = eventCount(groupId, "USER_BLOCKED")
        val notificationsBeforeRace = notificationCount(memberId, "GROUP_MEMBER_BLOCKED")

        val winnerReactivateDone = CountDownLatch(1)
        val loserError = AtomicReference<Throwable?>()
        val blockedAtAfterWinner = AtomicReference<LocalDateTime?>()

        val winnerThread =
            Thread {
                TransactionTemplate(transactionManager).execute {
                    blockRepository.reactivate(
                        blockId = blockId,
                        groupId = groupId,
                        blockedBy = ownerId,
                        occurredAt = LocalDateTime.now(),
                    )
                    // 자신의 쓰기는 커밋 전에도 같은 트랜잭션에서 보인다(read-your-own-writes) —
                    // 나중에 패자가 이 값을 또 덮어썼는지 비교할 기준점을 여기서 캡처한다.
                    blockedAtAfterWinner.set(
                        blockRepository.findByGroupIdAndUserId(groupId, memberId)!!.blockedAt,
                    )
                    winnerReactivateDone.countDown()
                    // 패자가 같은 행에 reactivate() 를 시도해 잠금 대기열에 들어갈 시간을 번다.
                    Thread.sleep(300)
                }
            }

        val loserThread =
            Thread {
                try {
                    winnerReactivateDone.await(2, TimeUnit.SECONDS)
                    blockService.block(groupId, memberId, ownerId, "2차")
                } catch (e: Throwable) {
                    loserError.set(e)
                }
            }

        winnerThread.start()
        loserThread.start()
        winnerThread.join(5_000)
        loserThread.join(5_000)

        assertNull(loserError.get(), "이미 차단 상태에 도달했다면 패자도 예외 없이 성공해야 한다: ${loserError.get()}")

        val row = blockRow(memberId)
        assertNull(row.unblockedAt, "최종 상태는 차단 활성이어야 한다")
        assertEquals("2차", row.reason, "reason 갱신은 didBlock 여부와 무관하게 항상 적용된다")

        val captured = blockedAtAfterWinner.get()
        assertNotNull(captured, "승자 스레드가 자신의 reactivate() 를 마치지 못했다")
        assertEquals(
            captured,
            row.blockedAt,
            "패자가 confirm 이 아니라 직접 이겨서 blocked_at 을 다시 썼다면 이 값이 승자의 캡처값과 달라진다",
        )

        assertEquals(
            eventsBeforeRace,
            eventCount(groupId, "USER_BLOCKED"),
            "승자는 원시 리포지토리로 우회했고, 패자는 confirm 만 했으므로 경합 전후 이벤트 수는 늘지 않아야 한다",
        )
        assertEquals(
            notificationsBeforeRace,
            notificationCount(memberId, "GROUP_MEMBER_BLOCKED"),
            "승자는 원시 리포지토리로 우회했고, 패자는 confirm 만 했으므로 경합 전후 알림 수는 늘지 않아야 한다",
        )
    }

    private data class BlockRow(
        val reason: String?,
        val blockedAt: LocalDateTime,
        val unblockedAt: LocalDateTime?,
    )

    private fun blockRow(userId: UUID): BlockRow =
        jdbcTemplate.queryForObject(
            "SELECT reason, blocked_at, unblocked_at FROM search_group_user_block WHERE group_id = ? AND user_id = ?",
            { rs, _ ->
                BlockRow(
                    reason = rs.getString("reason"),
                    blockedAt = rs.getTimestamp("blocked_at").toLocalDateTime(),
                    unblockedAt = rs.getTimestamp("unblocked_at")?.toLocalDateTime(),
                )
            },
            groupId.toString(),
            userId.toString(),
        )!!

    private fun eventCount(
        forGroupId: UUID,
        type: String,
    ): Long =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM search_group_event WHERE group_id = ? AND type = ?",
            Long::class.java,
            forGroupId.toString(),
            type,
        )!!

    private fun notificationCount(
        userId: UUID,
        type: String,
    ): Long =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = ?",
            Long::class.java,
            userId.toString(),
            type,
        )!!

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
