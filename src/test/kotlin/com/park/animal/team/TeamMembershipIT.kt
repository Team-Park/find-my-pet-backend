package com.park.animal.team

import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.JpaConfig
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.NotificationService
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import com.park.animal.searchgroup.GroupNotificationPublisher
import com.park.animal.searchgroup.SearchGroupEventRecorder
import com.park.animal.searchgroup.SearchGroupTestMetricsConfig
import com.park.animal.searchgroup.access.AccessSource
import com.park.animal.searchgroup.access.GroupRole
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.SearchGroup
import com.park.animal.searchgroup.entity.SearchGroupTeam
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import com.park.animal.team.entity.TeamMember
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.entity.TeamStatus
import com.park.animal.team.repository.TeamMemberRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
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
 * 팀 · 팀 멤버십 · 팀장 이전 · 팀 보관 통합 테스트 (설계 §6.4, §22.2).
 *
 * - `uq_tm_single_active_leader` 가 "팀당 활성 팀장 1명" 불변식을 DB 로 강제하는지 확인한다.
 * - 팀원 변경이 수색그룹 권한에 즉시 반영되는지(파생 권한, 복사 없음) 고정한다.
 * - 팀 보관이 파생 권한을 회수하고, 그 뒤 마지막 팀장이 팀을 나갈 수 있는지 고정한다(설계 §6.4).
 * - 동시 승인 경합에서 confirm 이 `joinedAt` 을 훼손하지 않고 알림을 중복 발행하지 않는지 고정한다
 *   (코디네이터 지시 — Task 6/7 과 동일한 목표값→목표값 조건부 UPDATE 전략, `Isolation.READ_COMMITTED`
 *   금지).
 *
 * InnoDB 제약 위반과 조건부 UPDATE 의 실제 영향 행 수를 관찰해야 하므로 테스트 트랜잭션을 끈다.
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
)
@Testcontainers
class TeamMembershipIT {
    @Autowired lateinit var teamService: TeamService

    @Autowired lateinit var teamMembershipService: TeamMembershipService

    @Autowired lateinit var teamMemberRepository: TeamMemberRepository

    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var searchGroupRepository: SearchGroupRepository

    @Autowired lateinit var searchGroupTeamRepository: SearchGroupTeamRepository

    @Autowired lateinit var accessResolver: SearchGroupAccessResolver

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

    private fun savePost(ownerId: UUID): Post =
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

    /** 특정 팀이 ACTIVE 로 지원 중인 수색그룹 하나를 만든다. */
    private fun openGroupSupportedBy(
        ownerId: UUID,
        teamId: UUID,
        decidedBy: UUID,
    ): SearchGroup {
        val post = savePost(ownerId)
        val group = searchGroupRepository.save(SearchGroup(postId = post.id))
        val now = LocalDateTime.now()
        searchGroupTeamRepository.save(
            SearchGroupTeam(
                groupId = group.id,
                teamId = teamId,
                status = SearchGroupTeamStatus.ACTIVE,
                requestedBy = ownerId,
                requestedAt = now,
                decidedBy = decidedBy,
                decidedAt = now,
                activatedAt = now,
            ),
        )
        return group
    }

    @Test
    fun `팀을 만들면 생성자가 유일한 활성 팀장이 된다`() {
        val leaderId = UUID.randomUUID()

        val team = teamService.create(leaderId, "팀장", "한강 수색대", "야간 위주로 움직여요")

        assertEquals("한강 수색대", team.name)
        assertEquals(TeamRole.LEADER, team.viewerRole)
        assertEquals(TeamMemberStatus.ACTIVE, team.viewerStatus)
        assertEquals(1L, team.activeMemberCount)

        val leaders =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team_member WHERE team_id = ? AND role = 'LEADER' AND status = 'ACTIVE'",
                Int::class.java,
                team.id.toString(),
            )
        assertEquals(1, leaders)
    }

    @Test
    fun `두 번째 활성 팀장은 DB 제약이 막는다`() {
        val leaderId = UUID.randomUUID()
        val team = teamService.create(leaderId, "팀장", "야간 수색대", null)

        assertFailsWith<DataIntegrityViolationException> {
            teamMemberRepository.save(
                TeamMember(
                    teamId = team.id,
                    userId = UUID.randomUUID(),
                    userName = "가짜 팀장",
                    role = TeamRole.LEADER,
                    status = TeamMemberStatus.ACTIVE,
                    joinedAt = LocalDateTime.now(),
                    requestedAt = LocalDateTime.now(),
                    decidedAt = LocalDateTime.now(),
                    decidedBy = leaderId,
                ),
            )
        }
    }

    @Test
    fun `팀장 이전 후에도 활성 팀장은 정확히 한 명이다`() {
        val leaderId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val team = teamService.create(leaderId, "팀장", "주말 수색대", null)
        val requested = teamMembershipService.request(team.id, memberId, "새 팀원")
        teamMembershipService.approve(team.id, requested.id, leaderId)

        teamService.transferLeadership(team.id, leaderId, requested.id)

        val leaders =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team_member WHERE team_id = ? AND role = 'LEADER' AND status = 'ACTIVE'",
                Int::class.java,
                team.id.toString(),
            )
        assertEquals(1, leaders)

        val newLeader = teamMemberRepository.findByTeamIdAndUserId(team.id, memberId)
        assertNotNull(newLeader)
        assertEquals(TeamRole.LEADER, newLeader.role)

        val oldLeader = teamMemberRepository.findByTeamIdAndUserId(team.id, leaderId)
        assertNotNull(oldLeader)
        assertEquals(TeamRole.MEMBER, oldLeader.role)
        assertEquals(TeamMemberStatus.ACTIVE, oldLeader.status)
    }

    @Test
    fun `활성 팀의 팀장은 팀을 나갈 수 없다`() {
        val leaderId = UUID.randomUUID()
        val team = teamService.create(leaderId, "팀장", "새벽 수색대", null)

        val e = assertFailsWith<BusinessException> { teamMembershipService.leaveMe(team.id, leaderId) }
        assertEquals(ErrorCode.TEAM_LEADER_CANNOT_LEAVE, e.errorCode)
    }

    @Test
    fun `팀원이 팀을 나가면 그 팀이 지원하던 수색그룹 접근이 즉시 사라진다`() {
        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val memberId = UUID.randomUUID()

        val team = teamService.create(leaderId, "팀장", "한강 수색대", null)
        val requested = teamMembershipService.request(team.id, memberId, "팀원")
        teamMembershipService.approve(team.id, requested.id, leaderId)
        val group = openGroupSupportedBy(ownerId, team.id, leaderId)

        val before = accessResolver.resolve(group.id, memberId)
        assertNotNull(before)
        assertEquals(GroupRole.PARTICIPANT, before.role)
        assertTrue(AccessSource.TEAM in before.sources)

        teamMembershipService.leaveMe(team.id, memberId)

        val after = accessResolver.resolve(group.id, memberId)
        assertNotNull(after)
        assertEquals(GroupRole.NONE, after.role, "팀 탈퇴 즉시 파생 권한이 사라져야 한다")
    }

    @Test
    fun `다른 팀의 membershipId 로 승인하면 404`() {
        val leaderA = UUID.randomUUID()
        val leaderB = UUID.randomUUID()
        val applicant = UUID.randomUUID()
        val teamA = teamService.create(leaderA, "팀장A", "A 수색대", null)
        val teamB = teamService.create(leaderB, "팀장B", "B 수색대", null)
        val requestedInA = teamMembershipService.request(teamA.id, applicant, "신청자")

        val e =
            assertFailsWith<BusinessException> {
                teamMembershipService.approve(teamB.id, requestedInA.id, leaderB)
            }
        assertEquals(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP, e.errorCode)
    }

    @Test
    fun `팀을 보관하면 파생 권한이 회수되고 그 뒤 팀장도 팀을 나갈 수 있다`() {
        val ownerId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val memberId = UUID.randomUUID()

        val team = teamService.create(leaderId, "팀장", "해체 예정 수색대", null)
        val requested = teamMembershipService.request(team.id, memberId, "팀원")
        teamMembershipService.approve(team.id, requested.id, leaderId)
        val group = openGroupSupportedBy(ownerId, team.id, leaderId)

        val before = accessResolver.resolve(group.id, memberId)
        assertNotNull(before)
        assertEquals(GroupRole.PARTICIPANT, before.role)

        val archived = teamService.archive(team.id, leaderId)
        assertEquals(TeamStatus.ARCHIVED, archived.status)

        val support = searchGroupTeamRepository.findByGroupIdAndTeamId(group.id, team.id)
        assertNotNull(support)
        assertEquals(SearchGroupTeamStatus.WITHDRAWN, support.status, "보관은 활성 지원 연결을 회수한다")

        val after = accessResolver.resolve(group.id, memberId)
        assertNotNull(after)
        assertEquals(GroupRole.NONE, after.role, "팀 보관 즉시 파생 권한이 사라져야 한다")

        val left = teamMembershipService.leaveMe(team.id, leaderId)
        assertEquals(TeamMemberStatus.LEFT, left.status)
        assertEquals(TeamRole.MEMBER, left.role, "이탈 행에 LEADER 가 남지 않는다")
    }

    @Test
    fun `팀원은 팀을 보관할 수 없다`() {
        val leaderId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val team = teamService.create(leaderId, "팀장", "보관 시도 수색대", null)
        val requested = teamMembershipService.request(team.id, memberId, "팀원")
        teamMembershipService.approve(team.id, requested.id, leaderId)

        val e = assertFailsWith<BusinessException> { teamService.archive(team.id, memberId) }
        assertEquals(ErrorCode.TEAM_LEADER_REQUIRED, e.errorCode)
    }

    @Test
    fun `보관은 멱등하고 보관된 팀 상세 조회는 404`() {
        val leaderId = UUID.randomUUID()
        val team = teamService.create(leaderId, "팀장", "두 번 보관 수색대", null)

        assertEquals(TeamStatus.ARCHIVED, teamService.archive(team.id, leaderId).status)
        assertEquals(TeamStatus.ARCHIVED, teamService.archive(team.id, leaderId).status)

        val e = assertFailsWith<BusinessException> { teamService.detail(team.id, leaderId) }
        assertEquals(ErrorCode.NOT_FOUND_TEAM, e.errorCode)
    }

    @Test
    fun `팀 목록은 비로그인이면 viewer 필드가 null 이고 보관된 팀은 빠진다`() {
        val leaderId = UUID.randomUUID()
        val live = teamService.create(leaderId, "팀장", "살아있는 수색대", null)
        val gone = teamService.create(UUID.randomUUID(), "다른 팀장", "사라질 수색대", null)
        teamService.archive(gone.id, leaderOf(gone.id))

        val anonymous = teamService.list(null, 20L, 0L, null)
        assertEquals(listOf(live.id), anonymous.contents.map { it.id })
        assertNull(anonymous.contents.first().viewerRole)
        assertNull(anonymous.contents.first().viewerStatus)

        val asLeader = teamService.list(null, 20L, 0L, leaderId)
        assertEquals(TeamRole.LEADER, asLeader.contents.first().viewerRole)
        assertEquals(TeamMemberStatus.ACTIVE, asLeader.contents.first().viewerStatus)
    }

    /**
     * 동시 승인 경합 (설계 §16.1 REPEATABLE READ 트랩 회귀 방지, 코디네이터 지시).
     *
     * 승자는 원시 리포지토리로 `activate()` 를 직접 호출해 커밋 시점을 정확히 통제하고(Block/
     * 멤버십 IT 의 경합 테스트와 같은 기법), 패자는 실제 `teamMembershipService.approve(...)` 를
     * 부른다. 패자의 `activate()` 시도는 승자가 이미 커밋한 `status = ACTIVE` 를 최신 커밋 값
     * (current read)으로 재확인해 0 행을 반환해야 한다 — 이것이 "confirm 경로" 다. 이때 패자가
     * 목표값(ACTIVE) → 목표값(ACTIVE) 확인용 UPDATE 를 `transition()` 으로 쏘지 않고(버그로)
     * `activate()` 로 다시 쐈다면 `joinedAt` 이 패자의 (더 늦은) 확인 시각으로 덮어써진다.
     *
     * 증명 방법: 승자의 `activate()` 가 쓴 `joinedAt` 값을 승자 트랜잭션 안에서(자신의 쓰기이므로
     * 커밋 전에도 read-your-own-writes 로 보인다) 캡처해 두고, 최종 커밋된 `joinedAt` 이 그 값과
     * **정확히 같음**을 확인한다. 감사 기록에 해당하는 알림도 승자가 실제 서비스를 거치지 않았고
     * (원시 리포지토리 호출), 패자도 confirm 만 했으므로 경합 전후로 늘지 않아야 한다.
     */
    @Test
    fun `동시 팀원 승인 경합에서 패자는 확인만 하고 joinedAt 을 훼손하지 않으며 알림을 중복 남기지 않는다`() {
        val leaderId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val team = teamService.create(leaderId, "팀장", "동시 승인 수색대", null)
        val requested = teamMembershipService.request(team.id, memberId, "팀원")

        val notificationsBeforeRace = notificationCount(memberId, "TEAM_MEMBER_APPROVED")

        val winnerActivateDone = CountDownLatch(1)
        val loserError = AtomicReference<Throwable?>()
        val joinedAtAfterWinner = AtomicReference<LocalDateTime?>()

        val winnerThread =
            Thread {
                TransactionTemplate(transactionManager).execute {
                    teamMemberRepository.activate(
                        requested.id,
                        team.id,
                        TeamMemberStatus.PENDING,
                        leaderId,
                        LocalDateTime.now(),
                    )
                    // 자신의 쓰기는 커밋 전에도 같은 트랜잭션에서 보인다(read-your-own-writes) —
                    // 나중에 패자가 이 값을 또 덮어썼는지 비교할 기준점을 여기서 캡처한다.
                    joinedAtAfterWinner.set(
                        teamMemberRepository.findByIdAndTeamId(requested.id, team.id)!!.joinedAt,
                    )
                    winnerActivateDone.countDown()
                    // 패자가 같은 행에 activate() 를 시도해 잠금 대기열에 들어갈 시간을 번다.
                    Thread.sleep(300)
                }
            }

        val loserThread =
            Thread {
                try {
                    winnerActivateDone.await(2, TimeUnit.SECONDS)
                    teamMembershipService.approve(team.id, requested.id, leaderId)
                } catch (e: Throwable) {
                    loserError.set(e)
                }
            }

        winnerThread.start()
        loserThread.start()
        winnerThread.join(5_000)
        loserThread.join(5_000)

        assertNull(loserError.get(), "이미 ACTIVE 에 도달했다면 패자도 예외 없이 성공해야 한다: ${loserError.get()}")

        val row = teamMemberRepository.findByIdAndTeamId(requested.id, team.id)!!
        assertEquals(TeamMemberStatus.ACTIVE, row.status)

        val captured = joinedAtAfterWinner.get()
        assertNotNull(captured, "승자 스레드가 자신의 activate() 를 마치지 못했다")
        assertEquals(
            captured,
            row.joinedAt,
            "패자가 confirm 에서 activate() 를 다시 썼다면 joinedAt 이 패자의 (더 늦은) 시각으로 바뀐다",
        )

        assertEquals(
            notificationsBeforeRace,
            notificationCount(memberId, "TEAM_MEMBER_APPROVED"),
            "승자는 원시 리포지토리로 우회했고 패자는 confirm 만 했으므로 경합 전후 알림 수는 늘지 않아야 한다",
        )
    }

    /**
     * 팀장 이전(승격)과 내보내기 경합 (코디네이터 지적 — finding 1).
     *
     * `TeamMemberRepository.transition()`/`changeRole()` 의 WHERE 절은 `id`/`team_id`/`status`(또는
     * `role`)만 본다 — `remove()`/`leaveMe()` 가 "강등이 필요한지" 를 이 메서드 시작부의 (스테일할 수
     * 있는) 읽기로 분기하면, 동시에 그 행을 승격시키는 `transferLeadership()` 이 있을 때 REMOVED/LEFT
     * 행에 `role = LEADER` 가 그대로 남을 수 있다. 그러면 팀에 활성 팀장이 하나도 없어지고,
     * `archive()` 를 포함한 모든 팀장 전용 API 가 `requireActiveLeader` 에서 막혀 복구 경로가 없다.
     *
     * 승자는 원시 리포지토리로 `changeRole(강등) → changeRole(승격)` 을 직접 호출해(`transferLeadership()`
     * 의 실제 두 단계와 같은 순서) 커밋 시점을 정확히 통제하고, 패자는 실제 `teamMembershipService.
     * remove(...)` 를 부른다. 패자의 (finding 1 로 추가된) 무조건 강등 시도는 승자가 아직 커밋하지
     * 않은 동안 그 행의 잠금 대기열에 들어가고, 승자가 커밋해 그 행이 `LEADER`+`ACTIVE` 가 된 뒤에야
     * 재개된다 — current-read 로 최신 커밋 데이터를 확인하므로 이때는 실제로 강등(1행)이 일어난다.
     *
     * **이 경합이 증명하지 않는 것(정직하게 밝힌다)**: 이 특정 인터리빙(팀장 이전이 먼저 전부
     * 커밋된 뒤 내보내기가 재개)에서는 팀 전체가 일시적으로 무팀장 상태가 된다 — 이전 팀장은
     * 정당하게 강등됐고(팀장 이전 자체는 성공), 새로 승격된 팀원은 (스테일 읽기로 인해 이 경합을
     * 모른 채) 곧바로 내보내지기 때문이다. 이 fix 가 보장하는 것은 정확히 하나 — **어떤 REMOVED/LEFT
     * 행도 role = LEADER 를 남기지 않는다** — 이지 "이 경합에서 팀장이 반드시 살아남는다" 가 아니다
     * (그건 팀 전체 멤버십에 대한 비관적 락이나 SERIALIZABLE 격리가 있어야 가능한데 계약 F23 이
     * 비관적 락을 금지한다). "내보내기가 팀장 이전보다 먼저 커밋되는" 반대 인터리빙에서 팀장 이전이
     * 안전하게 409 로 실패해 팀장이 그대로 남는 경우는 별도 테스트(순차 시나리오)로 고정한다.
     */
    @Test
    fun `팀장 이전이 팀원을 승격하는 도중 그 팀원을 내보내면 내보내진 행은 role을 LEADER로 남기지 않는다`() {
        val leaderId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val team = teamService.create(leaderId, "팀장", "경합 수색대", null)
        val requested = teamMembershipService.request(team.id, memberId, "팀원")
        val member = teamMembershipService.approve(team.id, requested.id, leaderId)
        val leaderMembershipId =
            teamMemberRepository.findByTeamIdAndUserId(team.id, leaderId)!!.id

        val winnerPromoteDone = CountDownLatch(1)
        val loserError = AtomicReference<Throwable?>()

        val winnerThread =
            Thread {
                TransactionTemplate(transactionManager).execute {
                    // transferLeadership() 과 같은 순서: 강등 먼저, 승격 다음.
                    teamMemberRepository.changeRole(
                        leaderMembershipId,
                        team.id,
                        TeamRole.LEADER,
                        TeamRole.MEMBER,
                        LocalDateTime.now(),
                    )
                    teamMemberRepository.changeRole(
                        member.id,
                        team.id,
                        TeamRole.MEMBER,
                        TeamRole.LEADER,
                        LocalDateTime.now(),
                    )
                    winnerPromoteDone.countDown()
                    // 패자가 같은 행에 changeRole(강등) 을 시도해 잠금 대기열에 들어갈 시간을 번다.
                    Thread.sleep(300)
                }
            }

        val loserThread =
            Thread {
                try {
                    winnerPromoteDone.await(2, TimeUnit.SECONDS)
                    teamMembershipService.remove(team.id, member.id, leaderId)
                } catch (e: Throwable) {
                    loserError.set(e)
                }
            }

        winnerThread.start()
        loserThread.start()
        winnerThread.join(5_000)
        loserThread.join(5_000)

        assertNull(loserError.get(), "승격된 행이라도 내보내기 자체는 성공해야 한다: ${loserError.get()}")

        val removedRow = teamMemberRepository.findByIdAndTeamId(member.id, team.id)!!
        assertEquals(TeamMemberStatus.REMOVED, removedRow.status)
        assertEquals(TeamRole.MEMBER, removedRow.role, "REMOVED 행은 role 을 LEADER 로 남기지 않는다 — finding 1 의 핵심 단언")

        val anyTerminalRowCarriesLeader =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team_member WHERE team_id = ? AND role = 'LEADER' AND status IN ('REMOVED', 'LEFT', 'REJECTED')",
                Int::class.java,
                team.id.toString(),
            )
        assertEquals(0, anyTerminalRowCarriesLeader, "어떤 종료 상태 행도 role = LEADER 를 남기면 안 된다")
    }

    /**
     * 팀장 이전 대상이 이미 내보내진 뒤라면 팀장 이전은 안전하게 실패하고 팀장은 그대로 남는다
     * (코디네이터 지적 — finding 1, "패자가 아니라 승자가 되는" 반대 인터리빙).
     *
     * 위 경합 테스트와 달리 순차 실행만으로 재현된다 — `remove()` 가 완전히 커밋된 뒤에
     * `transferLeadership()` 이 그 membershipId 를 조회하면 이미 `status = REMOVED` 이므로 사전
     * 검증에서 바로 409 로 끝난다(강등 UPDATE 조차 시도하지 않는다). 이 경우 팀은 활성 팀장을
     * 정확히 하나 유지한다 — 팀장 이전이 실패해도 원래 팀장이 그대로 남기 때문이다.
     */
    @Test
    fun `내보내진 팀원을 팀장으로 이전하려 하면 409 이고 팀은 활성 팀장을 정확히 하나 유지한다`() {
        val leaderId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val team = teamService.create(leaderId, "팀장", "이전 대상 사전 제거 수색대", null)
        val requested = teamMembershipService.request(team.id, memberId, "팀원")
        val member = teamMembershipService.approve(team.id, requested.id, leaderId)

        teamMembershipService.remove(team.id, member.id, leaderId)

        val e =
            assertFailsWith<BusinessException> {
                teamService.transferLeadership(team.id, leaderId, member.id)
            }
        assertEquals(ErrorCode.SEARCH_GROUP_STATE_CONFLICT, e.errorCode)

        val activeLeaders =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team_member WHERE team_id = ? AND role = 'LEADER' AND status = 'ACTIVE'",
                Int::class.java,
                team.id.toString(),
            )
        assertEquals(1, activeLeaders, "팀장 이전이 실패해도 팀은 활성 팀장을 정확히 하나 유지해야 한다")
    }

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

    /** 팀 생성자(=활성 팀장)의 userId 를 되찾는다. */
    private fun leaderOf(teamId: UUID): UUID =
        teamMemberRepository
            .findFirstByTeamIdAndRoleAndStatus(teamId, TeamRole.LEADER, TeamMemberStatus.ACTIVE)!!
            .userId

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
