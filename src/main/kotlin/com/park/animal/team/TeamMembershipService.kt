package com.park.animal.team

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.entity.NotificationType
import com.park.animal.searchgroup.GroupNotificationPublisher
import com.park.animal.team.dto.TeamMembershipResponse
import com.park.animal.team.entity.Team
import com.park.animal.team.entity.TeamMember
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.entity.TeamStatus
import com.park.animal.team.repository.TeamMemberRepository
import com.park.animal.team.repository.TeamRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 팀 참여 요청 · 승인 · 거절 · 내보내기 · 탈퇴.
 *
 * 규칙:
 * - 재가입은 새 INSERT 가 아니라 기존 행의 status 전이다(`uq_tm_team_user`).
 * - 모든 전이는 `WHERE id = :id AND team_id = :teamId AND status = :expected` 조건부 UPDATE 다.
 *   영향 행 0 = 이미 다른 상태 → [reloadOrConflict] 로 재확인해서 목표 상태면 멱등 반환, 아니면 409.
 *   비관적 락은 쓰지 않는다(계약 F23).
 * - **재확인(confirm)은 평범한 SELECT 재조회로 하지 않는다.** MySQL(InnoDB) 기본 REPEATABLE READ
 *   에서는 트랜잭션 첫 읽기 시점에 스냅샷이 고정되므로, 경합에서 진 트랜잭션이 평범한 SELECT 로
 *   "이미 목표 상태인지" 를 물으면 상대가 방금 커밋한 값을 못 보고 오탐 409 를 낼 수 있다. 대신
 *   목표값 → 목표값 조건부 UPDATE(no-op 성격)를 쏜다 — InnoDB 는 이 UPDATE 의 WHERE 절을 현재
 *   커밋 데이터로 재평가하고(current-read), 이 쓰기 자체가 "이 트랜잭션의 변경"으로 기록되므로
 *   그 뒤의 평범한 재조회도 스냅샷과 무관하게 최신값을 본다(read-your-own-writes).
 * - **재확인은 항상 `transition()` 을 쓴다(`activate()` 아님)** — 목표가 ACTIVE 라도 `activate()`
 *   를 다시 쓰면 `joinedAt` 이 확인 시각으로 덮어써져 실제 참여 시각이 훼손된다. `transition()` 은
 *   status/decidedAt/decidedBy/updatedAt 만 건드리므로 `joinedAt` 은 항상 안전하다.
 * - **트랜잭션 전체의 격리 수준을 낮추지 않는다.** 한 트랜잭션 안에서 재조회 한 곳만 최신값을
 *   보면 되는 문제를 격리 수준 전체 완화로 풀면, 같은 메서드의 다른 읽기가 서로 다른 스냅샷을
 *   봐도 되는 비반복읽기 위험이 생긴다 — 실패하는 테스트가 없어 운영에서야 드러난다. `Task 6·7`
 *   이 이미 같은 값 조건부 UPDATE 로 통일했고 각각 `CountDownLatch` 경합 테스트로 검증돼 있다.
 * - LEFT / REMOVED 로 나가는 행은 role 을 MEMBER 로 되돌린다.
 * - 팀원 변경은 그대로 수색그룹 권한이다. 팀원 목록을 그룹 멤버 테이블로 복사하지 않으므로
 *   탈퇴·내보내기 직후 해당 팀이 지원 중인 모든 그룹의 파생 권한이 즉시 사라진다(설계 §6.6, §22.2).
 *   이 서비스에는 그룹 쪽으로 나가는 동기화 호출이 존재하지 않는 것이 정상이다.
 * - **보관된 팀**에서는 참여/승인/거절/내보내기가 막히지만 [leaveMe] 만은 허용된다.
 *   설계 §6.4 의 "보관 처리 후 팀장 이탈" 경로가 여기서 성립한다.
 */
@Service
class TeamMembershipService(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val notificationPublisher: GroupNotificationPublisher,
) {
    companion object {
        /** 팀원 목록 1회 조회 상한. phase 1 목록은 페이징 파라미터를 노출하지 않는다. */
        const val MAX_LIST_SIZE = 200L
    }

    @Transactional
    fun request(
        teamId: UUID,
        userId: UUID,
        userName: String?,
    ): TeamMembershipResponse {
        val team = requireActiveTeam(teamId)
        val now = LocalDateTime.now()
        val existing = teamMemberRepository.findByTeamIdAndUserId(teamId, userId)

        if (existing == null) {
            val saved =
                teamMemberRepository.save(
                    TeamMember(
                        teamId = teamId,
                        userId = userId,
                        userName = userName,
                        role = TeamRole.MEMBER,
                        status = TeamMemberStatus.PENDING,
                        joinedAt = null,
                        requestedAt = now,
                        decidedAt = null,
                        decidedBy = null,
                    ),
                )
            notifyLeader(team, actorUserId = userId)
            return TeamMembershipResponse.from(saved)
        }

        return when (existing.status) {
            // 이미 팀원이거나 이미 대기 중 — 재클릭/재시도는 같은 행 하나만 남긴다.
            TeamMemberStatus.ACTIVE, TeamMemberStatus.PENDING -> TeamMembershipResponse.from(existing)
            TeamMemberStatus.REJECTED, TeamMemberStatus.LEFT, TeamMemberStatus.REMOVED -> {
                // 인자 순서: (membershipId, teamId, expected, next, decidedBy, occurredAt)
                val moved =
                    teamMemberRepository.transition(
                        existing.id,
                        teamId,
                        existing.status,
                        TeamMemberStatus.PENDING,
                        null,
                        now,
                    )
                // 재신청 도중 다른 트랜잭션이 이미 PENDING 으로 되돌렸거나(동시 재신청), 한 걸음 더
                // 나가 곧바로 ACTIVE 로 승인까지 마쳤을 수 있다(재신청 직후 팀장이 즉시 승인). 둘 다
                // "다시 팀에 들어가려는" 이 요청의 의도가 이미 달성된 상태이므로 멱등 성공이다.
                val current =
                    reloadOrConflict(
                        teamId,
                        existing.id,
                        listOf(TeamMemberStatus.PENDING, TeamMemberStatus.ACTIVE),
                        moved,
                        null,
                    )
                if (moved == 0) return TeamMembershipResponse.from(current)

                // 이 트랜잭션이 실제로 전이시켰을 때만 아래 정규화·알림을 수행한다(gate).
                // 재신청은 같은 행의 전이다. 대기 행에 남아 있던 이전 결정 흔적을 지우고
                // 신청 시각·표시 이름만 새로 채운다(관리 대상 엔티티라 dirty checking 으로 반영된다).
                current.role = TeamRole.MEMBER
                current.requestedAt = now
                current.decidedAt = null
                current.decidedBy = null
                if (userName != null) current.userName = userName
                notifyLeader(team, actorUserId = userId)
                TeamMembershipResponse.from(current)
            }
        }
    }

    @Transactional
    fun approve(
        teamId: UUID,
        membershipId: UUID,
        leaderUserId: UUID,
    ): TeamMembershipResponse {
        val team = requireActiveTeam(teamId)
        requireActiveLeader(teamId, leaderUserId)
        // 설계 §16.1 — 다른 팀의 membershipId 는 tuple 조회에서 걸러져 404 가 된다.
        val target =
            teamMemberRepository.findByIdAndTeamId(membershipId, teamId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)

        val now = LocalDateTime.now()
        // 인자 순서: (membershipId, teamId, expected, decidedBy, occurredAt).
        // 실제 첫 활성화만 activate() 를 쓴다 — joinedAt 을 여기서 딱 한 번 찍기 위해서다.
        val moved = teamMemberRepository.activate(target.id, teamId, TeamMemberStatus.PENDING, leaderUserId, now)
        val current = reloadOrConflict(teamId, target.id, listOf(TeamMemberStatus.ACTIVE), moved, leaderUserId)

        // affected == 0 이면 이 호출은 confirm(다른 트랜잭션이 이미 ACTIVE 로 만든 것을 뒤늦게
        // 확인)일 뿐 실제 전이를 수행한 게 아니다 — 실제로 전이시킨 쪽만 알림을 남긴다.
        if (moved != 0) {
            notificationPublisher.notifyUser(
                userId = current.userId,
                type = NotificationType.TEAM_MEMBER_APPROVED,
                actorUserId = leaderUserId,
                postId = null,
                groupId = null,
                teamId = teamId,
                body = "'${team.name}' 팀의 팀원이 되었어요.",
            )
        }
        return TeamMembershipResponse.from(current)
    }

    @Transactional
    fun reject(
        teamId: UUID,
        membershipId: UUID,
        leaderUserId: UUID,
    ): TeamMembershipResponse {
        val team = requireActiveTeam(teamId)
        requireActiveLeader(teamId, leaderUserId)
        val target =
            teamMemberRepository.findByIdAndTeamId(membershipId, teamId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)

        val now = LocalDateTime.now()
        val moved =
            teamMemberRepository.transition(
                target.id,
                teamId,
                TeamMemberStatus.PENDING,
                TeamMemberStatus.REJECTED,
                leaderUserId,
                now,
            )
        val current = reloadOrConflict(teamId, target.id, listOf(TeamMemberStatus.REJECTED), moved, leaderUserId)

        if (moved != 0) {
            notificationPublisher.notifyUser(
                userId = current.userId,
                type = NotificationType.TEAM_MEMBER_REJECTED,
                actorUserId = leaderUserId,
                postId = null,
                groupId = null,
                teamId = teamId,
                body = "'${team.name}' 팀의 참여 요청이 받아들여지지 않았어요.",
            )
        }
        return TeamMembershipResponse.from(current)
    }

    @Transactional
    fun remove(
        teamId: UUID,
        membershipId: UUID,
        leaderUserId: UUID,
    ): TeamMembershipResponse {
        val team = requireActiveTeam(teamId)
        val leader = requireActiveLeader(teamId, leaderUserId)
        val target =
            teamMemberRepository.findByIdAndTeamId(membershipId, teamId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)
        // 팀장이 자기 자신을 내보내는 것은 팀장 이탈과 같다.
        if (target.id == leader.id) throw BusinessException(ErrorCode.TEAM_LEADER_CANNOT_LEAVE)

        val now = LocalDateTime.now()
        val moved =
            teamMemberRepository.transition(
                target.id,
                teamId,
                TeamMemberStatus.ACTIVE,
                TeamMemberStatus.REMOVED,
                leaderUserId,
                now,
            )
        val current = reloadOrConflict(teamId, target.id, listOf(TeamMemberStatus.REMOVED), moved, leaderUserId)

        if (moved != 0) {
            // 행위자·사유를 대상자에게 알리지 않는다 (설계 §6.3).
            notificationPublisher.notifyUser(
                userId = current.userId,
                type = NotificationType.TEAM_MEMBER_REMOVED,
                actorUserId = null,
                postId = null,
                groupId = null,
                teamId = teamId,
                body = "'${team.name}' 팀에서 나오게 되었어요.",
            )
        }
        return TeamMembershipResponse.from(current)
    }

    /**
     * 팀 나가기.
     *
     * 활성 팀의 팀장은 나갈 수 없다(409 `TEAM_LEADER_CANNOT_LEAVE`) — 후임에게 넘기거나
     * 팀을 보관해야 한다(설계 §6.4). 보관된 팀에서는 팀장도 나갈 수 있고, 이때 이력 정합을 위해
     * role 을 MEMBER 로 먼저 되돌린 뒤 status 를 전이한다.
     */
    @Transactional
    fun leaveMe(
        teamId: UUID,
        userId: UUID,
    ): TeamMembershipResponse {
        val team = requireTeam(teamId)
        val me =
            teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)
        val isActiveLeader = me.status == TeamMemberStatus.ACTIVE && me.role == TeamRole.LEADER
        if (isActiveLeader && team.status == TeamStatus.ACTIVE) {
            throw BusinessException(ErrorCode.TEAM_LEADER_CANNOT_LEAVE)
        }
        if (me.status == TeamMemberStatus.LEFT) return TeamMembershipResponse.from(me)
        if (me.status != TeamMemberStatus.ACTIVE && me.status != TeamMemberStatus.PENDING) {
            throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }

        val now = LocalDateTime.now()
        val membershipId = me.id
        val expected = me.status
        if (isActiveLeader) {
            // 보관된 팀의 마지막 팀장. LEFT 행에 LEADER 가 남지 않도록 먼저 강등한다.
            teamMemberRepository.changeRole(membershipId, teamId, TeamRole.LEADER, TeamRole.MEMBER, now)
        }

        val moved =
            teamMemberRepository.transition(membershipId, teamId, expected, TeamMemberStatus.LEFT, userId, now)
        val current = reloadOrConflict(teamId, membershipId, listOf(TeamMemberStatus.LEFT), moved, userId)
        return TeamMembershipResponse.from(current)
    }

    /** 팀장은 승인 대기까지, 그 외 로그인 사용자는 활성 팀원만 본다. */
    @Transactional(readOnly = true)
    fun list(
        teamId: UUID,
        viewerId: UUID,
    ): List<TeamMembershipResponse> {
        val team = requireTeam(teamId)
        val viewer = teamMemberRepository.findByTeamIdAndUserId(team.id, viewerId)
        val viewerIsLeader = viewer != null && viewer.status == TeamMemberStatus.ACTIVE && viewer.role == TeamRole.LEADER
        if (!viewerIsLeader) {
            return teamMemberRepository
                .findAllByTeamIdAndStatus(team.id, TeamMemberStatus.ACTIVE)
                .map(TeamMembershipResponse::from)
        }
        // Task 2 의 findAllByTeamIdOrderByCreatedAtDescIdDesc 는 Pageable 을 받지 않는다
        // (SearchGroupMemberRepository/SearchGroupTeamRepository/SearchGroupEventRepository 의
        // 동일 이름 메서드와 같은 규약) — 상한은 여기서 take() 로 건다.
        return teamMemberRepository
            .findAllByTeamIdOrderByCreatedAtDescIdDesc(team.id)
            .asSequence()
            .filter { it.status == TeamMemberStatus.ACTIVE || it.status == TeamMemberStatus.PENDING }
            .take(MAX_LIST_SIZE.toInt())
            .map(TeamMembershipResponse::from)
            .toList()
    }

    private fun notifyLeader(
        team: Team,
        actorUserId: UUID,
    ) {
        val leader =
            teamMemberRepository.findFirstByTeamIdAndRoleAndStatus(
                team.id,
                TeamRole.LEADER,
                TeamMemberStatus.ACTIVE,
            ) ?: return
        notificationPublisher.notifyUser(
            userId = leader.userId,
            type = NotificationType.TEAM_MEMBER_REQUESTED,
            actorUserId = actorUserId,
            postId = null,
            groupId = null,
            teamId = team.id,
            body = "'${team.name}' 팀에 새 참여 요청이 도착했어요.",
        )
    }

    /**
     * 영향 행 0 이면(경합에서 진 경우) [acceptableTargets] 각각에 대해 target → target 같은 값
     * 조건부 UPDATE 로 재확인한다. 하나라도 매칭되면(1행) 그 목표에 이미 도달한 것이라 멱등 성공,
     * 전부 매칭되지 않으면(모두 0행) 진짜 충돌(409)이다.
     *
     * 영향 행이 있으면(이 트랜잭션이 실제로 전이시킨 경우) 그 결과를 그대로 재조회해 반환한다 —
     * 자신이 방금 쓴 값은 REPEATABLE READ 스냅샷과 무관하게 항상 보인다(read-your-own-writes).
     *
     * **재확인은 항상 `transition()` 을 쓴다(`activate()` 아님).** [acceptableTargets] 에 ACTIVE 가
     * 있어도 `transition()` 은 status/decidedAt/decidedBy/updatedAt 만 SET 하고 `joinedAt` 을
     * 건드리지 않으므로, confirm 이 실제 최초 활성화 시각을 덮어쓸 수 없다. `activate()` 를 confirm
     * 에 썼다면 매 idempotent 승인 확인마다 `joinedAt` 이 확인 시각으로 재기록됐을 것이다 — Task 6
     * 에서 실제로 재현된 버그와 같은 종류다.
     *
     * 재확인은 실제 상태 전이가 아니므로 감사 기록과 알림은 여기서 절대 남기지 않는다 — 호출부가
     * `affected != 0` 일 때만 그 두 가지를 남기도록 각자 책임진다(이 메서드는 반환값만 준다).
     */
    private fun reloadOrConflict(
        teamId: UUID,
        membershipId: UUID,
        acceptableTargets: List<TeamMemberStatus>,
        affected: Int,
        decidedBy: UUID?,
    ): TeamMember {
        if (affected == 0) {
            val now = LocalDateTime.now()
            val confirmed =
                acceptableTargets.any { target ->
                    teamMemberRepository.transition(membershipId, teamId, target, target, decidedBy, now) != 0
                }
            if (!confirmed) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }
        return teamMemberRepository.findByIdAndTeamId(membershipId, teamId)
            ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)
    }

    private fun requireTeam(teamId: UUID): Team =
        teamRepository.findByIdAndDeletedAtIsNull(teamId)
            ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM)

    private fun requireActiveTeam(teamId: UUID): Team {
        val team = requireTeam(teamId)
        if (team.status != TeamStatus.ACTIVE) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        return team
    }

    private fun requireActiveLeader(
        teamId: UUID,
        userId: UUID,
    ): TeamMember {
        val me = teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
        if (me == null || me.status != TeamMemberStatus.ACTIVE || me.role != TeamRole.LEADER) {
            throw BusinessException(ErrorCode.TEAM_LEADER_REQUIRED)
        }
        return me
    }
}
