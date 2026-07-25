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
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchgroup.repository.SearchGroupAccessQueryRepository
import com.park.animal.searchgroup.repository.SearchGroupMemberRepository
import com.park.animal.searchgroup.repository.SearchGroupRepository
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
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

    @Autowired lateinit var searchGroupRepository: SearchGroupRepository

    @Autowired lateinit var memberRepository: SearchGroupMemberRepository

    @Autowired lateinit var transactionManager: PlatformTransactionManager

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

    private fun currentJoinPolicy(forGroupId: UUID): JoinPolicy =
        JoinPolicy.valueOf(
            jdbcTemplate.queryForObject(
                "SELECT join_policy FROM search_group WHERE id = ?",
                String::class.java,
                forGroupId.toString(),
            )!!,
        )

    private fun updatedAtOfGroup(forGroupId: UUID): LocalDateTime =
        jdbcTemplate
            .queryForObject(
                "SELECT updated_at FROM search_group WHERE id = ?",
                java.sql.Timestamp::class.java,
                forGroupId.toString(),
            )!!
            .toLocalDateTime()

    private fun memberStatus(membershipId: UUID): SearchGroupMemberStatus =
        SearchGroupMemberStatus.valueOf(
            jdbcTemplate.queryForObject(
                "SELECT status FROM search_group_member WHERE id = ?",
                String::class.java,
                membershipId.toString(),
            )!!,
        )

    private fun decidedAtOfMember(membershipId: UUID): LocalDateTime =
        jdbcTemplate
            .queryForObject(
                "SELECT decided_at FROM search_group_member WHERE id = ?",
                java.sql.Timestamp::class.java,
                membershipId.toString(),
            )!!
            .toLocalDateTime()

    /** 팀을 통해서만 권한을 얻은 사용자 — 직접 멤버십 행은 만들지 않는다(설계 §6.6). */
    private fun teamOnlyUser(forGroupId: UUID): UUID {
        val teamId = UUID.randomUUID()
        val leaderId = UUID.randomUUID()
        val member = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO team (id, name, description, status, created_by, created_at, updated_at) " +
                "VALUES (?, '테스트 팀', NULL, 'ACTIVE', ?, NOW(6), NOW(6))",
            teamId.toString(), leaderId.toString(),
        )
        jdbcTemplate.update(
            "INSERT INTO team_member (id, team_id, user_id, user_name, role, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, NULL, ?, ?, NOW(6), NOW(6))",
            UUID.randomUUID().toString(), teamId.toString(), member.toString(),
            TeamRole.MEMBER.name, TeamMemberStatus.ACTIVE.name,
        )
        jdbcTemplate.update(
            "INSERT INTO search_group_team (id, group_id, team_id, status, requested_by, requested_at, " +
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6), NOW(6))",
            UUID.randomUUID().toString(), forGroupId.toString(), teamId.toString(),
            SearchGroupTeamStatus.ACTIVE.name, leaderId.toString(),
        )
        return member
    }

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
    fun `동시 정책 변경 경합에서 패자도 이미 목표 상태에 도달했으면 예외 없이 성공한다`() {
        // SearchGroupRepository.updateJoinPolicyFrom 의 계약(0 영향행 = confirm 으로 재확인) 을
        // 서비스가 지키는지 확인한다. "승자" 는 저수준 리포지토리로 직접 전이시키고, 자신의 UPDATE
        // 가 실제로 실행된 직후(커밋 전) latch 로 신호를 보낸 뒤에야 커밋을 지연시킨다. "패자"
        // (검증 대상, membershipService 를 통한 진짜 서비스 호출)는 그 신호를 받은 뒤에야 출발
        // 하므로, 승자가 행을 잠그고 있는 동안 반드시 그 잠금을 만난다 — Thread.sleep 으로 순서를
        // 추측하지 않는다(관측 가능한 사건으로 순서를 강제한다).
        //
        // 패자가 confirm 분기를 실제로 탔는지는 updated_at 으로 직접 검증한다: confirm 은 목표값 →
        // 목표값 조건부 UPDATE 라 매칭되면 updated_at 을 다시 찍는다. 패자가 (스케줄링 등으로) 이
        // 레이스에 전혀 참여하지 못하고 그냥 자기 스냅샷으로 조기 반환했다면 updated_at 은 승자의
        // 캡처값과 같을 것이고, 아래 assertTrue 가 그 경우를 실패로 잡는다.
        val gid = newGroup(newPost(ownerId), JoinPolicy.OPEN)
        val winnerUpdateDone = CountDownLatch(1)
        val loserError = AtomicReference<Throwable?>()
        val updatedAtAfterWinnerUpdate = AtomicReference<LocalDateTime?>()

        val winnerThread =
            Thread {
                TransactionTemplate(transactionManager).execute {
                    searchGroupRepository.updateJoinPolicyFrom(
                        groupId = gid,
                        expected = JoinPolicy.OPEN,
                        next = JoinPolicy.APPROVAL_REQUIRED,
                        activeStatus = SearchGroupStatus.ACTIVE,
                        now = LocalDateTime.now(),
                    )
                    // 자신의 쓰기는 커밋 전에도 같은 트랜잭션에서 보인다(read-your-own-writes) —
                    // 나중에 confirm 이 이 값을 다시 덮어썼는지 비교할 기준점을 여기서 캡처한다.
                    updatedAtAfterWinnerUpdate.set(searchGroupRepository.findByIdAndDeletedAtIsNull(gid)!!.updatedAt)
                    winnerUpdateDone.countDown()
                    // 패자가 같은 행에 UPDATE 를 시도해 잠금 대기열에 들어갈 시간을 번다.
                    Thread.sleep(300)
                }
            }

        val loserThread =
            Thread {
                try {
                    winnerUpdateDone.await(2, TimeUnit.SECONDS)
                    membershipService.updateJoinPolicy(gid, ownerId, JoinPolicy.APPROVAL_REQUIRED)
                } catch (e: Throwable) {
                    loserError.set(e)
                }
            }

        winnerThread.start()
        loserThread.start()
        winnerThread.join(5_000)
        loserThread.join(5_000)

        assertNull(loserError.get(), "이미 목표 정책에 도달했다면 패자도 예외 없이 성공해야 한다: ${loserError.get()}")
        assertEquals(JoinPolicy.APPROVAL_REQUIRED, currentJoinPolicy(gid))

        val captured = updatedAtAfterWinnerUpdate.get()
        assertNotNull(captured, "승자 스레드가 자신의 UPDATE 를 마치지 못했다")
        assertTrue(
            updatedAtOfGroup(gid).isAfter(captured),
            "패자의 confirm UPDATE 가 실행되지 않았다 — updated_at 이 승자 시점과 같다(confirm 분기가 " +
                "검증되지 않았다는 뜻)",
        )
    }

    @Test
    fun `동시 승인 경합에서 패자도 이미 ACTIVE 에 도달했으면 예외 없이 성공하고 이벤트·알림은 승자만 남긴다`() {
        // reloadOrConflict 의 confirm 경로(정책 변경 경로와 같은 기법)를 approve() 에서 검증한다.
        // 순서 통제 방식과 confirm 분기 검증 방식은 위 정책 경합 테스트와 동일 — latch 로 순서를
        // 강제하고, 실제 갱신 컬럼(여기서는 decided_at)이 승자 이후 한 번 더 찍혔는지로 confirm 이
        // 실제로 실행됐음을 직접 증명한다.
        val approvalGroupId = newGroup(newPost(ownerId), JoinPolicy.APPROVAL_REQUIRED)
        val pending = membershipService.join(approvalGroupId, joinerId, "이참여")
        val membershipId = pending.membership.membershipId

        val winnerUpdateDone = CountDownLatch(1)
        val loserError = AtomicReference<Throwable?>()
        val decidedAtAfterWinnerUpdate = AtomicReference<LocalDateTime?>()

        val winnerThread =
            Thread {
                TransactionTemplate(transactionManager).execute {
                    memberRepository.activate(
                        membershipId = membershipId,
                        groupId = approvalGroupId,
                        expected = SearchGroupMemberStatus.PENDING,
                        decidedBy = ownerId,
                        occurredAt = LocalDateTime.now(),
                    )
                    decidedAtAfterWinnerUpdate.set(
                        memberRepository.findByIdAndGroupId(membershipId, approvalGroupId)!!.decidedAt,
                    )
                    winnerUpdateDone.countDown()
                    // 패자가 같은 행에 activate() 를 시도해 잠금 대기열에 들어갈 시간을 번다.
                    Thread.sleep(300)
                }
            }

        val loserThread =
            Thread {
                try {
                    winnerUpdateDone.await(2, TimeUnit.SECONDS)
                    membershipService.approve(approvalGroupId, membershipId, ownerId)
                } catch (e: Throwable) {
                    loserError.set(e)
                }
            }

        winnerThread.start()
        loserThread.start()
        winnerThread.join(5_000)
        loserThread.join(5_000)

        assertNull(loserError.get(), "이미 ACTIVE 에 도달했다면 패자도 예외 없이 성공해야 한다: ${loserError.get()}")
        assertEquals(SearchGroupMemberStatus.ACTIVE, memberStatus(membershipId))
        // 승자는 실제 서비스가 아니라 저수준 리포지토리로 직접 전이시켰으므로(타이밍을 정확히
        // 통제하기 위해서) 승자 쪽에서도 이벤트·알림이 없다 — 여기서 증명해야 할 것은 "패자
        // (confirm 경로)가 추가로 이벤트·알림을 만들지 않는다" 는 것뿐이다. "정상 approve() 호출은
        // 이벤트·알림을 정확히 1건 남긴다" 는 이미 다른 테스트(`APPROVAL_REQUIRED 는 신청 승인 거절
        // 양측 알림을 남긴다`)가 증명한다 — 두 테스트를 합치면 "총합은 항상 최대 1건, 그것도 실제
        // 전이시킨 쪽만" 이 성립한다.
        assertEquals(
            0L,
            eventCount(approvalGroupId, "MEMBER_APPROVED"),
            "confirm 만 한 패자는 감사 기록을 남기지 않는다(승자도 실제 서비스를 거치지 않아 0건이 맞다)",
        )
        assertEquals(
            0L,
            notificationCount(joinerId),
            "confirm 만 한 패자는 알림을 만들지 않는다(승자도 실제 서비스를 거치지 않아 0건이 맞다)",
        )

        val captured = decidedAtAfterWinnerUpdate.get()
        assertNotNull(captured, "승자 스레드가 자신의 activate() 를 마치지 못했다")
        assertTrue(
            decidedAtOfMember(membershipId).isAfter(captured),
            "패자의 confirm UPDATE 가 실행되지 않았다 — decided_at 이 승자 시점과 같다(confirm 분기가 " +
                "검증되지 않았다는 뜻)",
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
        // 설계 §6.6 — 팀원은 search_group_member 에 행을 갖지 않는다. 권한은 ACTIVE 팀지원 +
        // ACTIVE 팀 멤버십에서만 파생되므로, 직접 멤버십이 없는 이 사용자에게 leaveMe 는 404 다.
        val teamMemberUserId = teamOnlyUser(groupId)

        val e = assertFailsWith<BusinessException> { membershipService.leaveMe(groupId, teamMemberUserId) }
        assertEquals(ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP, e.errorCode)
    }

    @Test
    fun `이미 LEFT 인 참여를 다시 종료해도 멱등 200 이고 같은 행 그대로다`() {
        // 계약 §16 — 도달 가능한 목표 상태에 이미 있으면 항상 200. 재탈퇴는 새 전이도, 새 이벤트도
        // 만들지 않는다. 409 는 "그 상태에 도달할 수 없을 때"(REJECTED/REMOVED)만 쓴다.
        val joined = membershipService.join(groupId, joinerId, "이참여")
        val firstLeave = membershipService.leaveMe(groupId, joinerId)
        assertEquals(SearchGroupMemberStatus.LEFT, firstLeave.status)
        assertEquals(1L, eventCount(groupId, "MEMBER_LEFT"))

        val secondLeave = membershipService.leaveMe(groupId, joinerId)

        assertEquals(joined.membership.membershipId, secondLeave.membershipId)
        assertEquals(SearchGroupMemberStatus.LEFT, secondLeave.status)
        assertEquals(1L, memberRowCount(joinerId), "재종료가 새 행을 만들면 안 된다")
        assertEquals(1L, eventCount(groupId, "MEMBER_LEFT"), "멱등 재종료는 두 번째 활동 기록을 남기면 안 된다")
    }

    @Test
    fun `내보내진 참여자는 개인 참여 종료로 재종료할 수 없다`() {
        // REMOVED 는 보호자가 강제한 상태다 — 사용자가 스스로 "탈퇴를 마무리" 할 수 있는 상태가
        // 아니므로 LEFT 와 달리 계속 409 다.
        val joined = membershipService.join(groupId, joinerId, "이참여")
        membershipService.remove(groupId, joined.membership.membershipId, ownerId)

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
