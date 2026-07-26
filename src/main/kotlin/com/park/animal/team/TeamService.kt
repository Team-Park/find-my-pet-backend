package com.park.animal.team

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.entity.NotificationType
import com.park.animal.searchgroup.GroupNotificationPublisher
import com.park.animal.searchgroup.SearchGroupEventRecorder
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import com.park.animal.team.dto.TeamResponse
import com.park.animal.team.dto.TeamSummaryResponse
import com.park.animal.team.entity.Team
import com.park.animal.team.entity.TeamMember
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.entity.TeamStatus
import com.park.animal.team.repository.TeamMemberRepository
import com.park.animal.team.repository.TeamRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 팀 생성 · 탐색 · 수정 · 팀장 이전 · 보관.
 *
 * 입력 검증은 서비스에서 명시 코드로 한다 — 이 레포에는 `spring-boot-starter-validation` 이 없어
 * `@Valid` / `@field:NotBlank` 가 무동작이다.
 *
 * 팀원 구성은 수색그룹 권한의 **파생 원본**이다. 팀원을 각 그룹 멤버 테이블로 복사하지 않으므로
 * 이 서비스의 어떤 변경도 수색그룹 쪽에 별도 동기화 코드를 필요로 하지 않는다(설계 §6.6, §22.2).
 * 예외가 딱 하나 있는데 **팀 보관**이다 — 팀이 사라지면 `search_group_team` 의 ACTIVE 연결도
 * 함께 회수해야 파생 권한이 남지 않는다([archive]).
 *
 * **동시성**: 상태를 바꾸는 메서드는 비관적 락을 쓰지 않는다(계약 F23). 안전성은
 * `UPDATE ... WHERE id = :id AND team_id = :teamId AND <expected>` 조건부 전이가 보장한다.
 *
 * 영향 행 0(경합에서 진 경우)의 idempotency 판정은 **평범한 SELECT 재조회로 하지 않는다** —
 * MySQL(InnoDB) 기본 REPEATABLE READ 는 트랜잭션의 첫 읽기 시점에 스냅샷을 고정하므로, 그 뒤의
 * 평범한 SELECT 는 경합 상대가 방금 커밋한 값을 보지 못하고 "이미 목표 상태" 인데도 오탐 409 를
 * 낼 수 있다. 대신 목표값 → 목표값 조건부 UPDATE(no-op 성격, `archiveOrConflict`)로 확인한다 —
 * 이 UPDATE 는 WHERE 절을 현재 커밋 데이터 기준으로 다시 평가하고(InnoDB current-read), 그 자체가
 * "이 트랜잭션의 쓰기"로 기록되므로 그 뒤의 평범한 재조회도 스냅샷과 무관하게 최신값을 본다
 * (read-your-own-writes). `SearchGroupMembershipService.reloadOrConflict` 와 같은 기법이다.
 *
 * **`Isolation.READ_COMMITTED` 로 트랜잭션 전체를 낮추는 방식을 쓰지 않는다.** 그 방식도 좁은
 * 문제(재조회 1건)는 풀지만 두 가지 이유로 채택하지 않았다.
 * 1. 격리 수준은 메서드의 재조회 한 곳이 아니라 트랜잭션 전체에 적용된다. [archive] 는
 *    [SearchGroupAccessResolver] 의 native 조회를 루프 안에서 반복 호출하는데(`withdrawActiveSupports`),
 *    READ_COMMITTED 아래서는 같은 메서드 안의 나중 읽기가 앞선 읽기와 합리적으로 다른 값을 볼 수
 *    있다 — 실패하는 테스트 없이 조용히 존재하다 운영에서 드러나는 종류의 비반복읽기 위험이다.
 * 2. 이 기능 안에 idempotency 메커니즘이 두 개 생긴다. Task 6·7 은 이미 목표값 → 목표값 조건부
 *    UPDATE 로 통일했고 각각 `CountDownLatch` 경합 테스트로 검증돼 있다. 유지보수자가 팀 코드를
 *    건드릴 때마다 "이 경로는 어떤 메커니즘을 쓰는지" 를 따로 기억해야 하는 비용을 만들지 않는다.
 */
@Service
class TeamService(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val searchGroupTeamRepository: SearchGroupTeamRepository,
    private val accessResolver: SearchGroupAccessResolver,
    private val eventRecorder: SearchGroupEventRecorder,
    private val notificationPublisher: GroupNotificationPublisher,
) {
    companion object {
        const val NAME_MIN_LENGTH = 2
        const val NAME_MAX_LENGTH = 30
        const val DESCRIPTION_MAX_LENGTH = 200
        const val MAX_PAGE_SIZE = 50L
    }

    data class TeamPageResult(
        val contents: List<TeamSummaryResponse>,
        val hasNextPage: Boolean,
        val totalCount: Long,
    )

    @Transactional
    fun create(
        userId: UUID,
        userName: String?,
        name: String,
        description: String?,
    ): TeamResponse {
        val team =
            teamRepository.save(
                Team(
                    name = normalizeName(name),
                    description = normalizeDescription(description),
                    status = TeamStatus.ACTIVE,
                    createdBy = userId,
                ),
            )
        val now = LocalDateTime.now()
        teamMemberRepository.save(
            TeamMember(
                teamId = team.id,
                userId = userId,
                userName = userName,
                role = TeamRole.LEADER,
                status = TeamMemberStatus.ACTIVE,
                joinedAt = now,
                requestedAt = now,
                decidedAt = now,
                decidedBy = userId,
            ),
        )
        return detailOf(team, viewerId = userId)
    }

    /**
     * 공개 팀 목록. 활성 팀만 이름순으로 내려간다.
     *
     * 조회자의 멤버십은 ACTIVE / PENDING 두 번의 사용자 스코프 조회로 한 번에 모은다.
     * 활성 팀원 수는 페이지당 최대 [MAX_PAGE_SIZE] 건이므로 팀별 count 로 충분하다.
     */
    @Transactional(readOnly = true)
    fun list(
        q: String?,
        size: Long,
        offset: Long,
        viewerId: UUID?,
    ): TeamPageResult {
        val pageSize = size.coerceIn(1L, MAX_PAGE_SIZE).toInt()
        val pageIndex = (offset.coerceAtLeast(0L) / pageSize).toInt()
        val page =
            teamRepository.searchByName(
                q?.trim().orEmpty(),
                TeamStatus.ACTIVE,
                PageRequest.of(pageIndex, pageSize),
            )
        val viewerMemberships = viewerId?.let { loadViewerMemberships(it) } ?: emptyMap()
        return TeamPageResult(
            contents =
                page.content.map { team ->
                    TeamSummaryResponse.of(
                        team = team,
                        activeMemberCount = teamMemberRepository.countByTeamIdAndStatus(team.id, TeamMemberStatus.ACTIVE),
                        viewer = viewerMemberships[team.id],
                    )
                },
            hasNextPage = page.hasNext(),
            totalCount = page.totalElements,
        )
    }

    /** 보관된 팀은 공개 조회에서 존재하지 않는 것으로 취급한다(404). */
    @Transactional(readOnly = true)
    fun detail(
        teamId: UUID,
        viewerId: UUID?,
    ): TeamResponse {
        val team = requireTeam(teamId)
        if (team.status != TeamStatus.ACTIVE) throw BusinessException(ErrorCode.NOT_FOUND_TEAM)
        return detailOf(team, viewerId)
    }

    @Transactional
    fun update(
        teamId: UUID,
        leaderUserId: UUID,
        name: String,
        description: String?,
    ): TeamResponse {
        val team = requireActiveTeam(teamId)
        requireActiveLeader(teamId, leaderUserId)
        team.name = normalizeName(name)
        team.description = normalizeDescription(description)
        return detailOf(team, viewerId = leaderUserId)
    }

    /**
     * 팀장 이전. 한 트랜잭션 안에서 **강등 UPDATE → 승격 UPDATE** 순서로 두 번 실행한다.
     *
     * `CASE WHEN` 단일 UPDATE 로 합치면 `uq_tm_single_active_leader` (STORED generated column) 가
     * 행 단위로 검사되어 스캔 순서에 따라 1062 duplicate key 가 난다.
     *
     * 두 조건부 UPDATE 는 모두 InnoDB current-read 로 WHERE 절을 최신 커밋 데이터 기준으로
     * 평가하므로, 영향 행이 0 이면 그 시점에 대상이 더 이상 기대한 role/status 가 아니라는
     * 뜻이고 이는 진짜 충돌이다 — 별도의 목표값 확인(confirm) 재시도가 필요 없다(브리프 원안과
     * 동일). "같은 이전을 두 번 제출" 하는 이중 클릭은 두 번째 호출이 `requireActiveLeader` 에서
     * 이미 팀장이 아니게 된 사용자를 만나 403 로 끝나는 경로로 자연히 흡수된다.
     */
    @Transactional
    fun transferLeadership(
        teamId: UUID,
        currentLeaderUserId: UUID,
        targetMembershipId: UUID,
    ): TeamResponse {
        val team = requireActiveTeam(teamId)
        val leader = requireActiveLeader(teamId, currentLeaderUserId)
        val target =
            teamMemberRepository.findByIdAndTeamId(targetMembershipId, teamId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP)
        if (target.id == leader.id) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        if (target.status != TeamMemberStatus.ACTIVE || target.role != TeamRole.MEMBER) {
            throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }

        val now = LocalDateTime.now()
        val leaderMembershipId = leader.id
        val targetMembership = target.id
        val targetUserId = target.userId
        val teamName = team.name

        // 인자 순서: (membershipId, teamId, expected, next, occurredAt)
        val demoted =
            teamMemberRepository.changeRole(leaderMembershipId, teamId, TeamRole.LEADER, TeamRole.MEMBER, now)
        if (demoted == 0) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)

        val promoted =
            teamMemberRepository.changeRole(targetMembership, teamId, TeamRole.MEMBER, TeamRole.LEADER, now)
        if (promoted == 0) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)

        notificationPublisher.notifyUser(
            userId = targetUserId,
            type = NotificationType.TEAM_LEADERSHIP_TRANSFERRED,
            actorUserId = currentLeaderUserId,
            postId = null,
            groupId = null,
            teamId = teamId,
            body = "'$teamName' 팀의 팀장이 되었어요.",
        )
        // 전임 팀장에게는 결과 확인 알림이 필요하다. actorUserId 를 실어 보내면 publisher 의
        // skipSelf 규칙에 걸려 사라지므로 행위자 없이 발행한다(설계 §9).
        notificationPublisher.notifyUser(
            userId = currentLeaderUserId,
            type = NotificationType.TEAM_LEADERSHIP_TRANSFERRED,
            actorUserId = null,
            postId = null,
            groupId = null,
            teamId = teamId,
            body = "'$teamName' 팀의 팀장 권한을 넘겼어요.",
        )

        return detailOf(requireTeam(teamId), viewerId = currentLeaderUserId)
    }

    /**
     * 팀 보관 (설계 §6.4 "후임에게 이전 **또는 팀을 보관 처리**"). 팀장 전용.
     *
     * 순서가 곧 안전성이다.
     * 1. `team.status` ACTIVE → ARCHIVED 조건부 전이. 영향 행 0 이면 [archiveOrConflict] 가
     *    목표값(ARCHIVED) → 목표값(ARCHIVED) 조건부 UPDATE 로 재확인해 이미 보관됐으면 멱등 성공,
     *    아니면 409. 이 재확인이 실제 전이가 아니므로, 그 경우 아래 2·3 단계는 건너뛴다
     *    (`moved != 0` 게이트) — confirm-only 트랜잭션이 지원 회수·알림을 중복 발행하지 않는다.
     * 2. 그 팀의 ACTIVE `search_group_team` 을 전부 WITHDRAWN 으로 전이해 **파생 권한을 회수**한다.
     *    이걸 빼먹으면 보관된 팀의 팀원이 계속 수색그룹을 읽는다.
     * 3. 활성 팀원 전원에게 `TEAM_ARCHIVED` 알림.
     *
     * 보관 후에는 [TeamMembershipService.leaveMe] 가 팀장 이탈을 허용한다 — 마지막 팀장이
     * 팀에 영원히 묶이는 막다른 길을 없애는 것이 이 API 의 존재 이유다.
     */
    @Transactional
    fun archive(
        teamId: UUID,
        leaderUserId: UUID,
    ): TeamResponse {
        requireTeam(teamId)
        requireActiveLeader(teamId, leaderUserId)

        val now = LocalDateTime.now()
        // 인자 순서: (teamId, expected, next, occurredAt)
        val moved = teamRepository.archiveIfStatus(teamId, TeamStatus.ACTIVE, TeamStatus.ARCHIVED, now)
        val team = archiveOrConflict(teamId, moved, now)

        if (moved != 0) {
            withdrawActiveSupports(teamId, leaderUserId, now)
            notificationPublisher.notifyTeamMembers(
                teamId = teamId,
                groupId = null,
                postId = null,
                type = NotificationType.TEAM_ARCHIVED,
                excluding = setOf(leaderUserId),
                actorUserId = leaderUserId,
                body = null,
            )
        }
        return detailOf(team, viewerId = leaderUserId)
    }

    /**
     * `archiveIfStatus` 가 영향 행 0 을 돌려준(경합에서 진) 경우, 목표값(ARCHIVED) → 목표값(ARCHIVED)
     * 조건부 UPDATE 로 재확인한다. 매칭되면(1행) 이미 보관됐다는 뜻이라 멱등 성공, 매칭되지
     * 않으면(0행) 진짜 충돌이다.
     *
     * 이 확인용 UPDATE 도 `updatedAt` 을 다시 찍는 부작용이 있지만(Task 6 의 `reloadOrConflict` 와
     * 같은 비용), `Team` 에는 `joinedAt` 같은 "최초 시각" 필드가 없으므로 훼손될 값이 없다.
     */
    private fun archiveOrConflict(
        teamId: UUID,
        affected: Int,
        occurredAt: LocalDateTime,
    ): Team {
        if (affected == 0) {
            val confirmed = teamRepository.archiveIfStatus(teamId, TeamStatus.ARCHIVED, TeamStatus.ARCHIVED, occurredAt)
            if (confirmed == 0) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }
        return requireTeam(teamId)
    }

    /**
     * 보관된 팀이 지원 중이던 모든 연결을 회수한다.
     *
     * 조건부 전이가 0행을 돌려주면(다른 경로가 먼저 종료한 경우) 조용히 건너뛴다 — 목표 상태가
     * 이미 달성된 것이므로 실패가 아니다. 이 메서드는 [archive] 가 실제로 전이시킨 경우에만
     * 호출되므로(`moved != 0` 게이트) 여기 도달한 시점 자체가 이미 "confirm 이 아니다" 를 뜻한다.
     */
    private fun withdrawActiveSupports(
        teamId: UUID,
        actorUserId: UUID,
        now: LocalDateTime,
    ) {
        val supports = searchGroupTeamRepository.findAllByTeamIdAndStatus(teamId, SearchGroupTeamStatus.ACTIVE)
        supports.forEach { support ->
            val groupId = support.groupId
            // 인자 순서: (supportId, groupId, expected, next, decidedBy, occurredAt)
            val moved =
                searchGroupTeamRepository.transition(
                    support.id,
                    groupId,
                    SearchGroupTeamStatus.ACTIVE,
                    SearchGroupTeamStatus.WITHDRAWN,
                    actorUserId,
                    now,
                )
            if (moved == 0) return@forEach

            eventRecorder.record(
                groupId = groupId,
                type = SearchGroupEventType.TEAM_SUPPORT_WITHDRAWN,
                actorId = actorUserId,
                targetId = teamId,
                detail = "ACTIVE -> WITHDRAWN (team archived)",
            )
            val access = accessResolver.resolve(groupId, actorUserId) ?: return@forEach
            if (access.ownerUserId != actorUserId) {
                notificationPublisher.notifyUser(
                    userId = access.ownerUserId,
                    type = NotificationType.TEAM_SUPPORT_ENDED,
                    actorUserId = actorUserId,
                    postId = access.postId,
                    groupId = groupId,
                    teamId = teamId,
                    body = null,
                )
            }
        }
    }

    private fun loadViewerMemberships(viewerId: UUID): Map<UUID, TeamMember> =
        (
            teamMemberRepository.findAllByUserIdAndStatus(viewerId, TeamMemberStatus.ACTIVE) +
                teamMemberRepository.findAllByUserIdAndStatus(viewerId, TeamMemberStatus.PENDING)
        ).associateBy { it.teamId }

    private fun detailOf(
        team: Team,
        viewerId: UUID?,
    ): TeamResponse {
        val viewer = viewerId?.let { teamMemberRepository.findByTeamIdAndUserId(team.id, it) }
        val viewerIsLeader = viewer != null && viewer.status == TeamMemberStatus.ACTIVE && viewer.role == TeamRole.LEADER
        return TeamResponse(
            id = team.id,
            name = team.name,
            description = team.description,
            status = team.status,
            activeMemberCount = teamMemberRepository.countByTeamIdAndStatus(team.id, TeamMemberStatus.ACTIVE),
            pendingMemberCount =
                if (viewerIsLeader) {
                    teamMemberRepository.countByTeamIdAndStatus(team.id, TeamMemberStatus.PENDING)
                } else {
                    0L
                },
            leaderName =
                teamMemberRepository
                    .findFirstByTeamIdAndRoleAndStatus(team.id, TeamRole.LEADER, TeamMemberStatus.ACTIVE)
                    ?.userName,
            viewerRole = viewer?.role,
            viewerStatus = viewer?.status,
            createdAt = team.createdAt,
        )
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

    private fun normalizeName(raw: String): String {
        val name = raw.trim()
        if (name.length !in NAME_MIN_LENGTH..NAME_MAX_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_COLLABORATION_INPUT)
        }
        return name
    }

    private fun normalizeDescription(raw: String?): String? {
        val description = raw?.trim()
        if (description.isNullOrEmpty()) return null
        if (description.length > DESCRIPTION_MAX_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_COLLABORATION_INPUT)
        }
        return description
    }
}
