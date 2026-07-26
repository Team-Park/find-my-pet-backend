package com.park.animal.searchgroup

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.notification.entity.NotificationType
import com.park.animal.searchgroup.access.GroupAccess
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.searchgroup.dto.SearchGroupTeamSupportResponse
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupStatus
import com.park.animal.searchgroup.entity.SearchGroupTeam
import com.park.animal.searchgroup.entity.SearchGroupTeamStatus
import com.park.animal.searchgroup.repository.SearchGroupTeamRepository
import com.park.animal.team.entity.Team
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.entity.TeamRole
import com.park.animal.team.entity.TeamStatus
import com.park.animal.team.repository.TeamMemberRepository
import com.park.animal.team.repository.TeamRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 팀 지원 연결(양방향) — 설계 §6.5, §8.4, §14.3.
 *
 * 방향 판정 규칙(서버 계산, 클라이언트 입력 금지):
 * - 액터가 보호자면 팀 승인 대기 `PENDING_TEAM_APPROVAL`
 * - 액터가 그 팀의 활성 팀장이면 보호자 승인 대기 `PENDING_GROUP_APPROVAL`
 * - 둘 다면 즉시 `ACTIVE` — 같은 사람이 자기 자신의 승인을 기다리는 막다른 길을 만들지 않는다.
 *   자동 활성화 사실은 감사 기록에 남긴다.
 * - 그 팀의 활성 팀원이지만 팀장이 아니면 403 `TEAM_LEADER_REQUIRED`
 *   (설계 §7 "우리 팀의 지원 종료 — 팀장만"). 팀과 아무 관계도 없으면 403 `SEARCH_GROUP_ACCESS_DENIED`.
 *
 * `(group_id, team_id)` 가 UNIQUE 이므로 재요청은 새 INSERT 가 아니라 기존 행의 조건부 status 전이다.
 *
 * **동시성**: 비관적 락을 쓰지 않는다(계약 F23). 트랜잭션 격리 수준은 기본값(REPEATABLE READ)을
 * 그대로 둔다 — 낮추지 않는다. 안전성은 `UPDATE ... WHERE id = :id AND group_id = :groupId AND
 * status = :expected` 조건부 전이가 보장하고, 영향 행이 0(경합에서 진 경우)이면 [reconfirmOrConflict]
 * 가 **목표값 → 목표값 조건부 UPDATE**(no-op 성격)로 재확인한다 — 평범한 SELECT 재조회를 쓰지 않는다.
 * MySQL(InnoDB) 기본 REPEATABLE READ 는 트랜잭션 첫 읽기 시점에 스냅샷을 고정하므로, 그 뒤의 평범한
 * SELECT 는 경합 상대가 방금 커밋한 값을 못 보고 "이미 목표 상태" 인데도 오탐 409 를 낼 수 있다.
 * 목표값 → 목표값 UPDATE 는 WHERE 절을 현재 커밋 데이터 기준으로 재평가하고(current-read), 그 자체가
 * "이 트랜잭션의 쓰기"로 기록되므로 그 뒤 재조회도 스냅샷과 무관하게 최신값을 본다
 * (read-your-own-writes). `TeamMembershipService.reloadOrConflict`/`TeamService.archiveOrConflict`
 * 와 같은 기법이다 — `Isolation.READ_COMMITTED` 로 트랜잭션 전체를 낮추는 방식은 두 가지 이유로
 * 채택하지 않는다. (1) 격리 수준은 재조회 한 곳이 아니라 트랜잭션 전체에 적용돼 이 서비스의 다른
 * 읽기(예: [SearchGroupAccessResolver] 조회)에도 비반복읽기 위험을 만든다. (2) 이 기능 안에
 * idempotency 메커니즘이 두 개 생겨 유지보수 비용이 늘어난다(Task 6~8 이 이미 통일한 방식과 다른
 * 방식을 하나 더 두는 셈이다).
 *
 * **재확인은 항상 `transition()` 을 쓴다(`activate()` 아님)** — 목표가 ACTIVE 라도 `activate()` 를
 * 다시 쓰면 `activated_at` 이 확인 시각으로 덮어써져 실제 활성화 시각이 훼손된다. `transition()` 은
 * status/decidedAt/decidedBy/updatedAt 만 SET 하므로 `activated_at` 은 항상 안전하다.
 *
 * **알림 본문**: 모든 알림 호출은 `body = null`(그리고 `actorName = null`)을 넘긴다. 팀 이름은
 * 사용자가 자유롭게 지은 텍스트라 다른 사용자의 알림에 그대로 보간하면 Task 5 템플릿 표의 금칙어·
 * 숫자 검사를 우회한다 — `TeamService.transferLeadership`/`archive`, `TeamMembershipService` 가 이미
 * 같은 실수를 겪고 고친 자리다(코디네이터 지적, "되돌리지 말 것"). `message` 파라미터도 같은 이유로
 * 알림 본문에 넣지 않는다 — 길이만 검증하고 버린다.
 *
 * **관측(설계 §20)**: 승인 대기 생성 시 [REQUESTED_COUNTER] 를 올린다. label 에는 방향만 넣고
 * id·메시지 본문·좌표는 넣지 않는다.
 */
@Service
class SearchGroupTeamSupportService(
    private val searchGroupTeamRepository: SearchGroupTeamRepository,
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val accessResolver: SearchGroupAccessResolver,
    private val eventRecorder: SearchGroupEventRecorder,
    private val notificationPublisher: GroupNotificationPublisher,
    private val meterRegistry: MeterRegistry,
) {
    companion object {
        const val MESSAGE_MAX_LENGTH = 200

        /** 목록 1회 조회 상한. phase 1 목록은 페이징 파라미터를 노출하지 않는다. */
        const val MAX_LIST_SIZE = 100

        const val REQUESTED_COUNTER = "fmp.searchgroup.team_support.requested"
    }

    /** 액터가 특정 팀에 대해 갖는 지위. 권한 오류를 403 두 종류로 정확히 가르기 위한 값이다. */
    private enum class TeamStanding { LEADER, MEMBER, OUTSIDER }

    @Transactional
    fun request(
        groupId: UUID,
        teamId: UUID,
        actorUserId: UUID,
        message: String? = null,
    ): SearchGroupTeamSupportResponse {
        val access = requireWritableGroup(groupId, actorUserId)
        val team = requireActiveTeam(teamId)
        validateMessage(message)

        val standing = standingIn(teamId, actorUserId)
        val initial =
            when {
                access.isOwner && standing == TeamStanding.LEADER -> SearchGroupTeamStatus.ACTIVE
                access.isOwner -> SearchGroupTeamStatus.PENDING_TEAM_APPROVAL
                standing == TeamStanding.LEADER -> SearchGroupTeamStatus.PENDING_GROUP_APPROVAL
                standing == TeamStanding.MEMBER -> throw BusinessException(ErrorCode.TEAM_LEADER_REQUIRED)
                else -> throw BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)
            }

        val now = LocalDateTime.now()
        val existing = searchGroupTeamRepository.findByGroupIdAndTeamId(groupId, teamId)

        if (existing == null) {
            val saved =
                searchGroupTeamRepository.save(
                    SearchGroupTeam(
                        groupId = groupId,
                        teamId = teamId,
                        status = initial,
                        requestedBy = actorUserId,
                        requestedAt = now,
                        decidedBy = if (initial == SearchGroupTeamStatus.ACTIVE) actorUserId else null,
                        decidedAt = if (initial == SearchGroupTeamStatus.ACTIVE) now else null,
                        activatedAt = if (initial == SearchGroupTeamStatus.ACTIVE) now else null,
                    ),
                )
            afterRequested(access, team, saved, actorUserId)
            return SearchGroupTeamSupportResponse.of(saved, team.name)
        }

        return when (existing.status) {
            // 이미 활성이거나 이미 대기 중 — 재전송은 같은 행 하나만 유지한다.
            SearchGroupTeamStatus.ACTIVE,
            SearchGroupTeamStatus.PENDING_GROUP_APPROVAL,
            SearchGroupTeamStatus.PENDING_TEAM_APPROVAL,
            -> SearchGroupTeamSupportResponse.of(existing, team.name)

            SearchGroupTeamStatus.DECLINED,
            SearchGroupTeamStatus.WITHDRAWN,
            SearchGroupTeamStatus.REMOVED,
            -> {
                // 되살아나는 행의 decidedBy: ACTIVE 목표(자기 자신이 보호자+팀장)는 결정자가 곧
                // 행위자이고, 대기 목표(PENDING_*)는 재요청이 결정이 아니므로 null 이다. 경합에서 진
                // 쪽의 확인(target→target)도 이 값을 그대로 재사용한다 — 이 경로의 모든 전이는
                // side-specific(그 순간 그 역할을 만족하는 actorUserId 가 유일)이라 같은 행에서
                // 경합하는 두 트랜잭션은 항상 같은 actorUserId 를 쓴다.
                val decidedByForAttempt = if (initial == SearchGroupTeamStatus.ACTIVE) actorUserId else null
                val moved =
                    if (initial == SearchGroupTeamStatus.ACTIVE) {
                        // 인자 순서: (supportId, groupId, expected, decidedBy, activatedAt)
                        searchGroupTeamRepository.activate(existing.id, groupId, existing.status, decidedByForAttempt, now)
                    } else {
                        // 인자 순서: (supportId, groupId, expected, next, decidedBy, occurredAt)
                        searchGroupTeamRepository.transition(existing.id, groupId, existing.status, initial, decidedByForAttempt, now)
                    }
                val current = reconfirmOrConflict(groupId, existing.id, initial, decidedByForAttempt, moved)
                if (moved == 0) return SearchGroupTeamSupportResponse.of(current, team.name)

                // 이 트랜잭션이 실제로 전이시켰을 때만 정규화·알림을 수행한다(gate) — requestedBy 는
                // 엔티티의 val 필드라 절대 바뀌지 않는다(최초 요청자 보존).
                current.requestedAt = now
                if (initial != SearchGroupTeamStatus.ACTIVE) {
                    current.decidedAt = null
                    current.activatedAt = null
                }
                afterRequested(access, team, current, actorUserId)
                SearchGroupTeamSupportResponse.of(current, team.name)
            }
        }
    }

    @Transactional
    fun accept(
        groupId: UUID,
        supportId: UUID,
        actorUserId: UUID,
    ): SearchGroupTeamSupportResponse {
        val access = requireWritableGroup(groupId, actorUserId)
        val support =
            searchGroupTeamRepository.findByIdAndGroupId(supportId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_SUPPORT)
        val teamId = support.teamId
        val team = requireActiveTeam(teamId)
        // 보호자는 팀이 제안한 건만, 팀장은 보호자가 요청한 건만 수락할 수 있다.
        val expected = expectedPendingFor(access, teamId, actorUserId, support.status)

        val now = LocalDateTime.now()
        val moved = searchGroupTeamRepository.activate(supportId, groupId, expected, actorUserId, now)
        val current = reconfirmOrConflict(groupId, supportId, SearchGroupTeamStatus.ACTIVE, actorUserId, moved)
        if (moved == 0) return SearchGroupTeamSupportResponse.of(current, team.name)

        eventRecorder.record(
            groupId = groupId,
            type = SearchGroupEventType.TEAM_SUPPORT_ACCEPTED,
            actorId = actorUserId,
            targetId = teamId,
            detail = "${expected.name} -> ACTIVE",
        )
        // 보호자가 이 팀의 활성 팀원이기도 하면(그룹 소유와 팀 멤버십은 서로 독립된 축이다) 아래
        // notifyOwner 가 보호자에게 따로 알림을 보낸다 — 여기서 함께 세면 같은 사람이 두 행을 받는다
        // (설계 §9 "여러 경로로 같은 그룹 권한을 가진 사용자는 알림을 한 번만 받는다"). 보호자는
        // 항상 notifyOwner 하나로만 받도록 팀 fan-out 에서 미리 제외한다.
        notificationPublisher.notifyTeamMembers(
            teamId = teamId,
            groupId = groupId,
            postId = access.postId,
            type = NotificationType.TEAM_SUPPORT_ACCEPTED,
            excluding = setOf(actorUserId, access.ownerUserId),
            actorUserId = actorUserId,
            body = null,
        )
        // 보호자가 행위자면 publisher 의 skipSelf 가 중복을 지운다 — 조건 분기를 두지 않는다.
        notificationPublisher.notifyOwner(
            access = access,
            type = NotificationType.TEAM_SUPPORT_ACCEPTED,
            actorUserId = actorUserId,
            actorName = null,
            body = null,
        )
        return SearchGroupTeamSupportResponse.of(current, team.name)
    }

    @Transactional
    fun decline(
        groupId: UUID,
        supportId: UUID,
        actorUserId: UUID,
    ): SearchGroupTeamSupportResponse {
        val access = requireWritableGroup(groupId, actorUserId)
        val support =
            searchGroupTeamRepository.findByIdAndGroupId(supportId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_SUPPORT)
        val teamId = support.teamId
        val team = requireActiveTeam(teamId)
        val expected = expectedPendingFor(access, teamId, actorUserId, support.status)

        val now = LocalDateTime.now()
        val moved =
            searchGroupTeamRepository.transition(
                supportId,
                groupId,
                expected,
                SearchGroupTeamStatus.DECLINED,
                actorUserId,
                now,
            )
        val current = reconfirmOrConflict(groupId, supportId, SearchGroupTeamStatus.DECLINED, actorUserId, moved)
        if (moved == 0) return SearchGroupTeamSupportResponse.of(current, team.name)

        eventRecorder.record(
            groupId = groupId,
            type = SearchGroupEventType.TEAM_SUPPORT_DECLINED,
            actorId = actorUserId,
            targetId = teamId,
            detail = "${expected.name} -> DECLINED",
        )
        // 통보 대상은 반대편이다. 대기 상태가 곧 방향이므로 requestedBy 에 의존하지 않는다.
        if (expected == SearchGroupTeamStatus.PENDING_GROUP_APPROVAL) {
            val leader =
                teamMemberRepository.findFirstByTeamIdAndRoleAndStatus(teamId, TeamRole.LEADER, TeamMemberStatus.ACTIVE)
            if (leader != null) {
                notificationPublisher.notifyUser(
                    userId = leader.userId,
                    type = NotificationType.TEAM_SUPPORT_DECLINED,
                    actorUserId = actorUserId,
                    postId = access.postId,
                    groupId = groupId,
                    teamId = teamId,
                    body = null,
                )
            }
        } else {
            notificationPublisher.notifyOwner(
                access = access,
                type = NotificationType.TEAM_SUPPORT_DECLINED,
                actorUserId = actorUserId,
                actorName = null,
                body = null,
            )
        }
        return SearchGroupTeamSupportResponse.of(current, team.name)
    }

    /**
     * 활성 지원 종료. 보호자면 `REMOVED`, 팀장이면 `WITHDRAWN`.
     *
     * 해당 연결 한 행만 전이하므로 같은 그룹의 다른 팀·직접 참여자에게는 영향이 없다(설계 §14.3).
     * 대기 중인 요청은 이 API 로 취소하지 않는다 — 상대의 거절(decline)로 정리한다.
     *
     * 보호자와 팀장이 동시에 종료를 시도하면(REMOVED vs WITHDRAWN) 서로 다른 목표를 향하므로, 늦게
     * 도착한 쪽의 확인(target→target)은 자신의 목표가 아닌 다른 종결 상태를 보고 실패(409)할 수
     * 있다 — 이 메서드는 "내가 의도한 특정 종료"만 멱등으로 취급하고, 상대가 다른 이유로 먼저
     * 끝냈다면 정직하게 409 를 돌려준다(상태가 바뀌었으니 다시 조회하라는 뜻).
     */
    @Transactional
    fun end(
        groupId: UUID,
        supportId: UUID,
        actorUserId: UUID,
    ): SearchGroupTeamSupportResponse {
        val access = requireWritableGroup(groupId, actorUserId)
        val support =
            searchGroupTeamRepository.findByIdAndGroupId(supportId, groupId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_SUPPORT)
        val teamId = support.teamId
        val team = requireActiveTeam(teamId)

        val standing = standingIn(teamId, actorUserId)
        val next =
            when {
                access.isOwner -> SearchGroupTeamStatus.REMOVED
                standing == TeamStanding.LEADER -> SearchGroupTeamStatus.WITHDRAWN
                standing == TeamStanding.MEMBER -> throw BusinessException(ErrorCode.TEAM_LEADER_REQUIRED)
                else -> throw BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)
            }

        val now = LocalDateTime.now()
        val moved =
            searchGroupTeamRepository.transition(
                supportId,
                groupId,
                SearchGroupTeamStatus.ACTIVE,
                next,
                actorUserId,
                now,
            )
        val current = reconfirmOrConflict(groupId, supportId, next, actorUserId, moved)
        if (moved == 0) return SearchGroupTeamSupportResponse.of(current, team.name)

        eventRecorder.record(
            groupId = groupId,
            type =
                if (next == SearchGroupTeamStatus.REMOVED) {
                    SearchGroupEventType.TEAM_SUPPORT_REMOVED
                } else {
                    SearchGroupEventType.TEAM_SUPPORT_WITHDRAWN
                },
            actorId = actorUserId,
            targetId = teamId,
            detail = "ACTIVE -> ${next.name}",
        )
        // notifyOwner 아래서 보호자를 따로 부르므로, 보호자가 이 팀의 활성 팀원이기도 해도 팀
        // fan-out 에서는 제외한다 — 같은 사람이 두 행을 받지 않게 한다(설계 §9).
        notificationPublisher.notifyTeamMembers(
            teamId = teamId,
            groupId = groupId,
            postId = access.postId,
            type = NotificationType.TEAM_SUPPORT_ENDED,
            excluding = setOf(actorUserId, access.ownerUserId),
            actorUserId = actorUserId,
            body = null,
        )
        notificationPublisher.notifyOwner(
            access = access,
            type = NotificationType.TEAM_SUPPORT_ENDED,
            actorUserId = actorUserId,
            actorName = null,
            body = null,
        )
        return SearchGroupTeamSupportResponse.of(current, team.name)
    }

    /**
     * 보호자는 전체 연결을, 팀장은 자기 팀의 연결만 본다.
     *
     * `requireRead` 를 쓴다 — `SearchGroupMembershipService.list()` 와 같은 규약이다. 차단된
     * 사용자와, 이 그룹에 대해 어떤 권한도 아직 없는 사용자(role = NONE) 는 조회할 수 없다.
     */
    @Transactional(readOnly = true)
    fun list(
        groupId: UUID,
        userId: UUID,
    ): List<SearchGroupTeamSupportResponse> {
        val access = accessResolver.requireRead(groupId, userId)
        // Task 2 의 findAllByGroupIdOrderByCreatedAtDescIdDesc 는 Pageable 을 받지 않는다
        // (TeamMemberRepository 의 동일 이름 메서드와 같은 규약) — 상한은 여기서 take() 로 건다.
        val rows =
            searchGroupTeamRepository
                .findAllByGroupIdOrderByCreatedAtDescIdDesc(groupId)
                .take(MAX_LIST_SIZE)
        val visible =
            if (access.isOwner) {
                rows
            } else {
                val myTeamIds =
                    teamMemberRepository
                        .findAllByUserIdAndStatus(userId, TeamMemberStatus.ACTIVE)
                        .filter { it.role == TeamRole.LEADER }
                        .map { it.teamId }
                        .toSet()
                if (myTeamIds.isEmpty()) throw BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)
                rows.filter { it.teamId in myTeamIds }
            }
        if (visible.isEmpty()) return emptyList()
        val names =
            teamRepository
                .findAllById(visible.map { it.teamId }.distinct())
                .associate { it.id to it.name }
        return visible.map { SearchGroupTeamSupportResponse.of(it, names[it.teamId]) }
    }

    /**
     * 수락·거절이 소비할 대기 상태를 방향으로 결정한다.
     *
     * 액터가 보호자이면서 동시에 그 팀의 팀장이면 지금 걸려 있는 대기 상태를 그대로 소비한다 —
     * 양쪽 권한을 다 가진 사람이 자기 요청을 스스로 처리하는 정상 경로다. `currentStatus` 는 호출
     * 시점에 한 번 읽은 값이라 stale 할 수 있지만, 실제 쓰기는 이 값을 그대로 신뢰하지 않는다 —
     * 여기서 고른 `expected` 는 조건부 UPDATE 의 WHERE 절로 다시 들어가 현재 커밋된 데이터 기준으로
     * 재평가되므로(current-read), 이 함수가 stale 값을 골랐어도 실제 전이는 안전하게 0 행으로
     * 끝난다(뒤이은 [reconfirmOrConflict] 가 처리한다).
     */
    private fun expectedPendingFor(
        access: GroupAccess,
        teamId: UUID,
        actorUserId: UUID,
        currentStatus: SearchGroupTeamStatus,
    ): SearchGroupTeamStatus {
        val standing = standingIn(teamId, actorUserId)
        return when {
            access.isOwner && standing == TeamStanding.LEADER ->
                if (currentStatus == SearchGroupTeamStatus.PENDING_TEAM_APPROVAL) {
                    SearchGroupTeamStatus.PENDING_TEAM_APPROVAL
                } else {
                    SearchGroupTeamStatus.PENDING_GROUP_APPROVAL
                }
            access.isOwner -> SearchGroupTeamStatus.PENDING_GROUP_APPROVAL
            standing == TeamStanding.LEADER -> SearchGroupTeamStatus.PENDING_TEAM_APPROVAL
            standing == TeamStanding.MEMBER -> throw BusinessException(ErrorCode.TEAM_LEADER_REQUIRED)
            else -> throw BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)
        }
    }

    private fun afterRequested(
        access: GroupAccess,
        team: Team,
        support: SearchGroupTeam,
        actorUserId: UUID,
    ) {
        when (support.status) {
            SearchGroupTeamStatus.ACTIVE -> {
                // 보호자 = 팀장. 승인 왕복 없이 활성화했다는 사실을 감사 기록에 남긴다.
                eventRecorder.record(
                    groupId = support.groupId,
                    type = SearchGroupEventType.TEAM_SUPPORT_REQUESTED,
                    actorId = actorUserId,
                    targetId = team.id,
                    detail = "auto-active: requester is both owner and team leader",
                )
                notificationPublisher.notifyTeamMembers(
                    teamId = team.id,
                    groupId = support.groupId,
                    postId = access.postId,
                    type = NotificationType.TEAM_SUPPORT_ACCEPTED,
                    excluding = setOf(actorUserId),
                    actorUserId = actorUserId,
                    body = null,
                )
            }

            SearchGroupTeamStatus.PENDING_GROUP_APPROVAL -> {
                eventRecorder.record(
                    groupId = support.groupId,
                    type = SearchGroupEventType.TEAM_SUPPORT_REQUESTED,
                    actorId = actorUserId,
                    targetId = team.id,
                    detail = "-> PENDING_GROUP_APPROVAL",
                )
                meterRegistry.counter(REQUESTED_COUNTER, "direction", "team_to_group").increment()
                notificationPublisher.notifyOwner(
                    access = access,
                    type = NotificationType.TEAM_SUPPORT_REQUESTED,
                    actorUserId = actorUserId,
                    actorName = null,
                    body = null,
                )
            }

            SearchGroupTeamStatus.PENDING_TEAM_APPROVAL -> {
                eventRecorder.record(
                    groupId = support.groupId,
                    type = SearchGroupEventType.TEAM_SUPPORT_REQUESTED,
                    actorId = actorUserId,
                    targetId = team.id,
                    detail = "-> PENDING_TEAM_APPROVAL",
                )
                meterRegistry.counter(REQUESTED_COUNTER, "direction", "owner_to_team").increment()
                val leader =
                    teamMemberRepository.findFirstByTeamIdAndRoleAndStatus(
                        team.id,
                        TeamRole.LEADER,
                        TeamMemberStatus.ACTIVE,
                    )
                if (leader != null) {
                    notificationPublisher.notifyUser(
                        userId = leader.userId,
                        type = NotificationType.TEAM_SUPPORT_REQUESTED,
                        actorUserId = actorUserId,
                        postId = access.postId,
                        groupId = support.groupId,
                        teamId = team.id,
                        body = null,
                    )
                }
            }

            SearchGroupTeamStatus.DECLINED,
            SearchGroupTeamStatus.WITHDRAWN,
            SearchGroupTeamStatus.REMOVED,
            -> Unit
        }
    }

    /**
     * 영향 행 0 이면(경합에서 진 경우) target → target 같은 값 조건부 UPDATE 로 재확인한다. 매칭되면
     * (1행) 이미 그 상태에 도달한 것이라 멱등 성공, 매칭되지 않으면(0행) 진짜 충돌(409)이다.
     *
     * `decidedBy` 는 실제 시도에 쓴 값을 그대로 재사용한다 — 이 서비스가 다루는 모든 전이는
     * side-specific(그 순간 그 역할을 만족하는 actorUserId 가 오직 하나)이라, 같은 행에서 경합하는
     * 두 트랜잭션은 항상 같은 actorUserId 를 쓴다(팀장 교체가 확인 도중 끼어드는 좁은 창은 여기서
     * 증명하지 않는다).
     *
     * 재확인은 실제 상태 전이가 아니므로 감사 기록과 알림은 여기서 절대 남기지 않는다 — 호출부가
     * `affected != 0` 일 때만 그 두 가지를 남기도록 각자 책임진다.
     */
    private fun reconfirmOrConflict(
        groupId: UUID,
        supportId: UUID,
        target: SearchGroupTeamStatus,
        decidedBy: UUID?,
        affected: Int,
    ): SearchGroupTeam {
        if (affected == 0) {
            val confirmed =
                searchGroupTeamRepository.transition(supportId, groupId, target, target, decidedBy, LocalDateTime.now()) != 0
            if (!confirmed) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        }
        return searchGroupTeamRepository.findByIdAndGroupId(supportId, groupId)
            ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM_SUPPORT)
    }

    private fun requireWritableGroup(
        groupId: UUID,
        userId: UUID,
    ): GroupAccess {
        val access = accessResolver.requireVisible(groupId, userId)
        // 차단이 활성인 동안 직접 가입뿐 아니라 팀 파생 권한에 대한 관리 레버(요청·수락·거절·종료)도
        // 모두 거부한다(설계 §6.3 "차단이 활성인 동안... 팀 파생 권한... 접근을 모두 거부한다", §6.6
        // "차단은 허용 권한보다 우선한다"). 비참여자와 같은 403 이어야 차단 사실이 응답으로 새어나가지
        // 않는다 — `SearchGroupMembershipService.joinRejection`/`leaveMe` 와 동일한 순서·코드다.
        if (access.blocked) throw BusinessException(ErrorCode.SEARCH_GROUP_ACCESS_DENIED)
        if (access.groupStatus != SearchGroupStatus.ACTIVE) throw BusinessException(ErrorCode.SEARCH_ALREADY_ENDED)
        return access
    }

    private fun requireActiveTeam(teamId: UUID): Team {
        val team =
            teamRepository.findByIdAndDeletedAtIsNull(teamId)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_TEAM)
        if (team.status != TeamStatus.ACTIVE) throw BusinessException(ErrorCode.SEARCH_GROUP_STATE_CONFLICT)
        return team
    }

    private fun standingIn(
        teamId: UUID,
        userId: UUID,
    ): TeamStanding {
        val membership = teamMemberRepository.findByTeamIdAndUserId(teamId, userId) ?: return TeamStanding.OUTSIDER
        if (membership.status != TeamMemberStatus.ACTIVE) return TeamStanding.OUTSIDER
        return if (membership.role == TeamRole.LEADER) TeamStanding.LEADER else TeamStanding.MEMBER
    }

    private fun validateMessage(raw: String?) {
        val message = raw?.trim()
        if (!message.isNullOrEmpty() && message.length > MESSAGE_MAX_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_COLLABORATION_INPUT)
        }
    }
}
