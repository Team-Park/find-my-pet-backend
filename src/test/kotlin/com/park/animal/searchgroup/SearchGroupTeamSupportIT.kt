package com.park.animal.searchgroup

import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.NotificationService
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.access.GroupRole
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.dto.SearchGroupTeamSupportResponse
import com.park.animal.searchgroup.entity.SearchGroup
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import com.park.animal.team.TeamMembershipService
import com.park.animal.team.TeamService
import io.micrometer.core.instrument.MeterRegistry
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 팀 지원 연결(양방향) 통합 테스트 — 설계 §6.5, §8.4, §14.3.
 *
 * 초기 status 는 클라이언트 입력이 아니라 서버가 tuple(group, post.authorId, team_member)로 계산한다.
 * 동시 수락 경합은 `TransactionTemplate` 으로 승자 트랜잭션의 커밋 시점을 정확히 통제해 재현한다
 * (Task 6~8 이 쓴 것과 같은 기법) — 두 스레드 모두 실제 `supportService.accept(...)` 를 통과시켜서,
 * "정확히 한 번의 알림" 이 실제 서비스 경로(승자)에서만 나오고 confirm 경로(패자)에서는 나오지
 * 않음을 증명한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    JpaConfig::class,
    SearchGroupTestMetricsConfig::class,
    NotificationService::class,
    SearchGroupAccessQueryRepository::class,
    SearchGroupAccessResolver::class,
    SearchGroupEventRecorder::class,
    GroupNotificationPublisher::class,
    TeamService::class,
    TeamMembershipService::class,
    SearchGroupTeamSupportService::class,
    SearchGroupBlockService::class,
)
@Testcontainers
class SearchGroupTeamSupportIT {
    @Autowired lateinit var supportService: SearchGroupTeamSupportService

    @Autowired lateinit var teamService: TeamService

    @Autowired lateinit var teamMembershipService: TeamMembershipService

    @Autowired lateinit var blockService: SearchGroupBlockService

    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var searchGroupRepository: SearchGroupRepository

    @Autowired lateinit var searchGroupTeamRepository: SearchGroupTeamRepository

    @Autowired lateinit var accessResolver: SearchGroupAccessResolver

    @Autowired lateinit var meterRegistry: MeterRegistry

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun clean() {
        listOf(
            "search_group_team",
            "search_group_member",
            "search_group_user_block",
            "search_group_event",
            "search_group",
            "team_member",
            "team",
            "notification",
            "post_bookmark",
            "post",
        ).forEach { jdbcTemplate.execute("DELETE FROM $it") }
    }

    private fun openGroup(ownerId: UUID): SearchGroup {
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
                    description = "역삼동에서 실종된 하얀 말티즈입니다.",
                    lat = 37.5012,
                    lng = 127.0396,
                    openChatUrl = null,
                    missingAnimalStatus = MissingAnimalStatus.SEARCHING,
                    animalType = AnimalType.DOG,
                ),
            )
        return searchGroupRepository.save(SearchGroup(postId = post.id))
    }

    private fun requestedCounterTotal(): Double =
        meterRegistry
            .find("fmp.searchgroup.team_support.requested")
            .counters()
            .sumOf { it.count() }

    @Test
    fun `팀장이 제안하면 보호자 승인 대기, 보호자가 요청하면 팀장 승인 대기`() {
        val ownerA = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val groupA = openGroup(ownerA)
        val team = teamService.create(leaderId, "팀장", "한강 수색대", null)

        val byLeader = supportService.request(groupA.id, team.id, leaderId, "저희가 돕고 싶어요")
        assertEquals(SearchGroupTeamStatus.PENDING_GROUP_APPROVAL, byLeader.status)

        val ownerB = UUID.randomUUID()
        val groupB = openGroup(ownerB)
        // message 를 생략해 기본값(null)을 함께 고정한다.
        val byOwner = supportService.request(groupB.id, team.id, ownerB)
        assertEquals(SearchGroupTeamStatus.PENDING_TEAM_APPROVAL, byOwner.status)

        // 보호자이면서 그 팀의 팀장이면 승인 왕복 없이 바로 활성화된다.
        val selfId = UUID.randomUUID()
        val groupC = openGroup(selfId)
        val selfTeam = teamService.create(selfId, "보호자겸팀장", "자체 수색대", null)
        val bySelf = supportService.request(groupC.id, selfTeam.id, selfId)
        assertEquals(SearchGroupTeamStatus.ACTIVE, bySelf.status)
        assertNotNull(bySelf.activatedAt)
    }

    @Test
    fun `팀원은 팀 지원을 요청할 수 없다`() {
        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val group = openGroup(ownerId)
        val team = teamService.create(leaderId, "팀장", "권한 확인 수색대", null)
        val membership = teamMembershipService.request(team.id, memberId, "팀원")
        teamMembershipService.approve(team.id, membership.id, leaderId)

        val e =
            assertFailsWith<BusinessException> {
                supportService.request(group.id, team.id, memberId)
            }
        assertEquals(ErrorCode.TEAM_LEADER_REQUIRED, e.errorCode, "팀과 무관한 사용자가 아니라 비팀장 팀원이다")

        val outsider = UUID.randomUUID()
        val e2 =
            assertFailsWith<BusinessException> {
                supportService.request(group.id, team.id, outsider)
            }
        assertEquals(ErrorCode.SEARCH_GROUP_ACCESS_DENIED, e2.errorCode)
    }

    @Test
    fun `요청 생성은 승인 대기 카운터를 올린다`() {
        val before = requestedCounterTotal()

        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val group = openGroup(ownerId)
        val team = teamService.create(leaderId, "팀장", "관측 수색대", null)
        supportService.request(group.id, team.id, leaderId)

        assertEquals(before + 1.0, requestedCounterTotal(), 0.0001)
    }

    /**
     * 동시 수락 경합 (설계 §16.1 REPEATABLE READ 트랩 회귀 방지).
     *
     * 승자는 `TransactionTemplate` 으로 실제 `supportService.accept(...)` 를 감싸 커밋 시점을
     * 정확히 통제한다 — accept() 자체가 `@Transactional`(REQUIRED)이라 이 트랜잭션에 참여하고,
     * 람다가 끝날 때(= sleep 이 끝난 뒤)까지 커밋되지 않는다. 패자는 같은 실제 서비스 메서드를
     * 부르므로, "실제 서비스 경로가 실행되면 알림이 정확히 1건" 을 두 스레드 모두 실제 코드를
     * 거친 채로 증명한다(승자가 원시 리포지토리로 우회하는 Task 6~8 식 경합 테스트와 다른 점).
     *
     * 잔여 가정: 두 accept() 호출의 actorUserId 가 같다(같은 보호자의 중복 클릭). 팀장 교체처럼
     * "그 순간 그 역할" 자체가 바뀌는 좁은 창은 이 테스트가 다루지 않는다 — RED 검증에서 이 창을
     * 넓히는 변경은 하지 않았다.
     */
    @Test
    fun `동시 수락 경합 - ACTIVE 는 한 행, 수락 알림도 한 번만`() {
        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val group = openGroup(ownerId)
        val team = teamService.create(leaderId, "팀장", "야간 수색대", null)
        val support = supportService.request(group.id, team.id, leaderId)
        assertEquals(SearchGroupTeamStatus.PENDING_GROUP_APPROVAL, support.status)

        val winnerAcceptDone = CountDownLatch(1)
        val winnerResult = AtomicReference<SearchGroupTeamSupportResponse>()
        val loserResult = AtomicReference<SearchGroupTeamSupportResponse>()
        val loserError = AtomicReference<Throwable?>()

        val winnerThread =
            Thread {
                TransactionTemplate(transactionManager).execute {
                    winnerResult.set(supportService.accept(group.id, support.id, ownerId))
                    winnerAcceptDone.countDown()
                    // 패자가 같은 행에 activate() 를 시도해 잠금 대기열에 들어갈 시간을 번다.
                    Thread.sleep(300)
                }
            }

        val loserThread =
            Thread {
                try {
                    winnerAcceptDone.await(2, TimeUnit.SECONDS)
                    loserResult.set(supportService.accept(group.id, support.id, ownerId))
                } catch (e: Throwable) {
                    loserError.set(e)
                }
            }

        winnerThread.start()
        loserThread.start()
        winnerThread.join(5_000)
        loserThread.join(5_000)

        assertNull(loserError.get(), "이미 ACTIVE 에 도달했다면 패자도 예외 없이 성공해야 한다: ${loserError.get()}")
        assertEquals(SearchGroupTeamStatus.ACTIVE, winnerResult.get()?.status)
        assertEquals(SearchGroupTeamStatus.ACTIVE, loserResult.get()?.status)

        val activeRows =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_group_team WHERE group_id = ? AND status = 'ACTIVE'",
                Int::class.java,
                group.id.toString(),
            )
        assertEquals(1, activeRows)

        val missingActivatedAt =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_group_team WHERE group_id = ? AND status = 'ACTIVE' AND activated_at IS NULL",
                Int::class.java,
                group.id.toString(),
            )
        assertEquals(0, missingActivatedAt, "ACTIVE 전이는 activated_at 을 반드시 채운다")

        assertEquals(
            winnerResult.get()?.activatedAt,
            loserResult.get()?.activatedAt,
            "패자의 confirm 이 activate() 를 다시 썼다면 activated_at 이 패자 시점으로 바뀌어 승자 값과 달라야 한다",
        )

        val acceptedNoti =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE type = 'TEAM_SUPPORT_ACCEPTED'",
                Int::class.java,
            )
        assertEquals(1, acceptedNoti, "수락 알림은 실제로 전이시킨 승자 쪽에서만 한 번 발행돼야 한다")
    }

    @Test
    fun `지원을 종료하면 팀원의 그룹 접근이 즉시 사라진다`() {
        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val group = openGroup(ownerId)
        val team = teamService.create(leaderId, "팀장", "주말 수색대", null)
        val membership = teamMembershipService.request(team.id, memberId, "팀원")
        teamMembershipService.approve(team.id, membership.id, leaderId)

        val support = supportService.request(group.id, team.id, ownerId)
        assertEquals(SearchGroupTeamStatus.PENDING_TEAM_APPROVAL, support.status)
        val accepted = supportService.accept(group.id, support.id, leaderId)
        assertEquals(SearchGroupTeamStatus.ACTIVE, accepted.status)

        val before = accessResolver.resolve(group.id, memberId)
        assertNotNull(before)
        assertEquals(GroupRole.PARTICIPANT, before.role)

        val ended = supportService.end(group.id, support.id, leaderId)
        assertEquals(SearchGroupTeamStatus.WITHDRAWN, ended.status)

        val after = accessResolver.resolve(group.id, memberId)
        assertNotNull(after)
        assertEquals(GroupRole.NONE, after.role, "지원 종료 즉시 파생 권한이 사라져야 한다")

        // 보호자와 다른 참여 경로에는 영향이 없다.
        val ownerAccess = accessResolver.resolve(group.id, ownerId)
        assertNotNull(ownerAccess)
        assertEquals(GroupRole.OWNER, ownerAccess.role)
    }

    @Test
    fun `다른 그룹의 supportId 로 수락하면 404 이고 원본 상태는 그대로다`() {
        val ownerA = UUID.randomUUID()
        val ownerB = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val groupA = openGroup(ownerA)
        val groupB = openGroup(ownerB)
        val team = teamService.create(leaderId, "팀장", "새벽 수색대", null)
        val supportInA = supportService.request(groupA.id, team.id, leaderId)

        val e =
            assertFailsWith<BusinessException> {
                supportService.accept(groupB.id, supportInA.id, ownerB)
            }
        assertEquals(ErrorCode.NOT_FOUND_TEAM_SUPPORT, e.errorCode)

        assertEquals(
            SearchGroupTeamStatus.PENDING_GROUP_APPROVAL,
            searchGroupTeamRepository.findByIdAndGroupId(supportInA.id, groupA.id)!!.status,
        )
    }

    /**
     * 차단 우선 원칙 (설계 §6.3 "차단이 활성인 동안... 팀 파생 권한... 접근을 모두 거부한다", §6.6
     * "차단은 허용 권한보다 우선한다"). 차단은 그룹 스코프, 팀장 지위는 팀 스코프라 서로 막지 않는다
     * — 보호자가 팀장을 차단해도 그 팀장은 여전히 다른 곳에서 팀장이다. 이 테스트는 차단된 팀장이
     * 자신이 이끄는 팀의 지원을 여전히 수락할 수 있는지(있어서는 안 된다)를 확인한다.
     */
    @Test
    fun `차단된 사용자는 팀 지원을 수락할 수 없다`() {
        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val group = openGroup(ownerId)
        val team = teamService.create(leaderId, "팀장", "차단 확인 수색대", null)
        // 보호자가 요청하면 팀장 승인 대기 — 팀장(leaderId)이 수락하는 경로다.
        val support = supportService.request(group.id, team.id, ownerId)
        assertEquals(SearchGroupTeamStatus.PENDING_TEAM_APPROVAL, support.status)

        blockService.block(group.id, leaderId, ownerId, "신뢰할 수 없음")

        val e = assertFailsWith<BusinessException> { supportService.accept(group.id, support.id, leaderId) }
        assertEquals(
            ErrorCode.SEARCH_GROUP_ACCESS_DENIED,
            e.errorCode,
            "차단된 사용자는 비참여자와 구분되지 않는 403 을 받아야 한다(차단 사실이 새어나가면 안 된다)",
        )

        assertEquals(
            SearchGroupTeamStatus.PENDING_TEAM_APPROVAL,
            searchGroupTeamRepository.findByIdAndGroupId(support.id, group.id)!!.status,
            "거부된 시도는 행을 건드리지 않는다",
        )
    }

    /**
     * 알림 중복 방지 (설계 §9 "여러 경로로 같은 그룹 권한을 가진 사용자는 알림을 한 번만 받는다").
     * 그룹 소유와 팀 멤버십은 서로 독립된 축이라, 보호자가 자신의 그룹을 지원하는 팀의 평범한
     * (팀장이 아닌) 활성 팀원일 수 있다 — 이 경우 `notifyTeamMembers` 와 `notifyOwner` 가 같은
     * 사람에게 각각 한 행씩 만들면 총 2건이 된다.
     */
    @Test
    fun `보호자가 팀의 활성 팀원이어도 팀 지원 수락 알림은 한 번만 받는다`() {
        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val group = openGroup(ownerId)
        val team = teamService.create(leaderId, "팀장", "혼합 소속 수색대", null)
        // 보호자가 이 팀의 (팀장이 아닌) 활성 팀원이 된다.
        val ownerMembership = teamMembershipService.request(team.id, ownerId, "보호자")
        teamMembershipService.approve(team.id, ownerMembership.id, leaderId)

        // 보호자가 요청하면 PENDING_TEAM_APPROVAL — 팀장이 수락해야 한다(actor != owner 라야
        // notifyOwner 의 skipSelf 로 우연히 통과하는 경우를 배제할 수 있다).
        val support = supportService.request(group.id, team.id, ownerId)
        assertEquals(SearchGroupTeamStatus.PENDING_TEAM_APPROVAL, support.status)

        val accepted = supportService.accept(group.id, support.id, leaderId)
        assertEquals(SearchGroupTeamStatus.ACTIVE, accepted.status)

        val ownerNotifications =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'TEAM_SUPPORT_ACCEPTED'",
                Int::class.java,
                ownerId.toString(),
            )
        assertEquals(1, ownerNotifications, "같은 사람이 팀 fan-out 과 보호자 알림 양쪽에서 중복으로 받으면 안 된다")
    }

    @Test
    fun `반대편의 대기 상태를 수락하려 하면 409 이고 원본 상태는 그대로다`() {
        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val group = openGroup(ownerId)
        val team = teamService.create(leaderId, "팀장", "교차 수락 확인 수색대", null)

        // 보호자가 요청 -> PENDING_TEAM_APPROVAL, 팀장만 수락할 수 있다. 보호자가 수락을 시도하면 409.
        val byOwner = supportService.request(group.id, team.id, ownerId)
        assertEquals(SearchGroupTeamStatus.PENDING_TEAM_APPROVAL, byOwner.status)
        val e1 = assertFailsWith<BusinessException> { supportService.accept(group.id, byOwner.id, ownerId) }
        assertEquals(ErrorCode.SEARCH_GROUP_STATE_CONFLICT, e1.errorCode)
        assertEquals(
            SearchGroupTeamStatus.PENDING_TEAM_APPROVAL,
            searchGroupTeamRepository.findByIdAndGroupId(byOwner.id, group.id)!!.status,
            "행이 훼손되지 않아야 한다",
        )

        // 다른 그룹에서: 팀장이 요청 -> PENDING_GROUP_APPROVAL, 보호자만 수락할 수 있다.
        // 팀장이 수락을 시도하면 409.
        val ownerB = UUID.randomUUID()
        val groupB = openGroup(ownerB)
        val byLeader = supportService.request(groupB.id, team.id, leaderId)
        assertEquals(SearchGroupTeamStatus.PENDING_GROUP_APPROVAL, byLeader.status)
        val e2 = assertFailsWith<BusinessException> { supportService.accept(groupB.id, byLeader.id, leaderId) }
        assertEquals(ErrorCode.SEARCH_GROUP_STATE_CONFLICT, e2.errorCode)
        assertEquals(
            SearchGroupTeamStatus.PENDING_GROUP_APPROVAL,
            searchGroupTeamRepository.findByIdAndGroupId(byLeader.id, groupB.id)!!.status,
            "행이 훼손되지 않아야 한다",
        )
    }

    @Test
    fun `보호자가 팀의 제안을 거절하면 DECLINED 이고 팀장에게 알림이 간다`() {
        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val group = openGroup(ownerId)
        val team = teamService.create(leaderId, "팀장", "거절 확인 수색대", null)
        val support = supportService.request(group.id, team.id, leaderId)
        assertEquals(SearchGroupTeamStatus.PENDING_GROUP_APPROVAL, support.status)

        val declined = supportService.decline(group.id, support.id, ownerId)
        assertEquals(SearchGroupTeamStatus.DECLINED, declined.status)

        val declinedNoti =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'TEAM_SUPPORT_DECLINED'",
                Int::class.java,
                leaderId.toString(),
            )
        assertEquals(1, declinedNoti, "거절당한 반대편(팀장)에게 알림이 가야 한다")
    }

    /**
     * `list()` 는 `requireRead` 를 쓴다(§20 픽스 라운드 finding 1과 짝을 이루는 finding — 코드
     * 리뷰). 팀장이 자기 뷰를 보려면 최소한 파생 권한(ACTIVE 지원)이 있어야 하므로, teamA 의 연결만
     * 활성화해 "팀장은 자기 팀의 연결만 본다" 를 참여 권한이 있는 상태에서 검증한다.
     */
    @Test
    fun `목록 조회는 보호자는 전체를, 팀장은 자기 팀의 연결만 본다`() {
        val ownerId = UUID.randomUUID()
        val leaderA = UUID.randomUUID()
        val leaderB = UUID.randomUUID()
        val group = openGroup(ownerId)
        val teamA = teamService.create(leaderA, "팀장A", "A 수색대", null)
        val teamB = teamService.create(leaderB, "팀장B", "B 수색대", null)

        val supportA = supportService.request(group.id, teamA.id, leaderA)
        val supportB = supportService.request(group.id, teamB.id, leaderB)
        supportService.accept(group.id, supportA.id, ownerId)

        val ownerView = supportService.list(group.id, ownerId)
        assertEquals(
            setOf(supportA.id, supportB.id),
            ownerView.map { it.id }.toSet(),
            "보호자는 대기 중인 연결까지 포함해 전체를 본다",
        )

        val leaderAView = supportService.list(group.id, leaderA)
        assertEquals(listOf(supportA.id), leaderAView.map { it.id }, "팀장은 자기 팀의 연결만 본다")
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
