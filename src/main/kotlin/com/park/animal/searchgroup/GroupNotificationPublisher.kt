package com.park.animal.searchgroup

import com.park.animal.notification.NotificationService
import com.park.animal.notification.entity.NotificationType
import com.park.animal.searchgroup.access.GroupAccess
import com.park.animal.searchgroup.access.SearchGroupAccessResolver
import com.park.animal.team.entity.TeamMemberStatus
import com.park.animal.team.repository.TeamMemberRepository
import org.springframework.stereotype.Service
import org.woo.apm.log.log
import java.util.UUID

/**
 * 수색그룹 알림의 단일 발행 지점 (설계 §9).
 *
 * 책임 세 가지.
 * 1. 수신자 계산: [SearchGroupAccessResolver.effectiveMemberIds] 결과에서 `excluding` 을 뺀다.
 *    보호자 ∪ 직접 ACTIVE ∪ 팀 ACTIVE − 활성 차단이 이미 Set 이므로, 여러 경로로 권한을 가진
 *    사용자가 **한 번만** 받는 지점이 바로 여기다. 호출부는 수신자를 직접 모으지 않는다.
 * 2. 문구: [GroupNotificationTemplates] 표만 사용한다. 호출부가 임의 제목을 만들지 않는다.
 * 3. fan-out 상한: 한 이벤트당 [MAX_FANOUT_PER_EVENT] 건을 넘지 않는다.
 */
@Service
class GroupNotificationPublisher(
    private val notificationService: NotificationService,
    private val accessResolver: SearchGroupAccessResolver,
    private val teamMemberRepository: TeamMemberRepository,
) {
    /** 보호자 1인에게. 행위자가 곧 보호자면 [NotificationService.createStructured] 의 skipSelf 가 걸러낸다. */
    fun notifyOwner(
        access: GroupAccess,
        type: NotificationType,
        actorUserId: UUID,
        actorName: String?,
        body: String?,
    ) {
        notificationService.createStructured(
            userId = access.ownerUserId,
            type = type,
            title = GroupNotificationTemplates.titleOf(type, actorName),
            body = body ?: GroupNotificationTemplates.bodyOf(type),
            link = GroupNotificationTemplates.linkOf(type, access.postId, access.groupId, null),
            actorUserId = actorUserId,
            actorName = actorName,
            postId = access.postId,
            groupId = access.groupId,
            skipSelf = true,
        )
    }

    /**
     * 지정 사용자 1인에게. 내보내기·차단처럼 행위자를 숨겨야 하는 이벤트는 `actorUserId = null` 로
     * 호출한다 — 행 자체에 행위자를 남기지 않는다(설계 §6.3).
     */
    fun notifyUser(
        userId: UUID,
        type: NotificationType,
        actorUserId: UUID?,
        postId: UUID?,
        groupId: UUID?,
        teamId: UUID?,
        body: String?,
    ) {
        notificationService.createStructured(
            userId = userId,
            type = type,
            title = GroupNotificationTemplates.titleOf(type, null),
            body = body ?: GroupNotificationTemplates.bodyOf(type),
            link = GroupNotificationTemplates.linkOf(type, postId, groupId, teamId),
            actorUserId = actorUserId,
            actorName = null,
            postId = postId,
            groupId = groupId,
            teamId = teamId,
            skipSelf = true,
        )
    }

    /** 그룹의 유효 참여자 전체에게. 중복 제거는 effectiveMemberIds 가 Set 이라는 사실로 보장된다. */
    fun notifyGroup(
        groupId: UUID,
        postId: UUID,
        type: NotificationType,
        excluding: Set<UUID>,
        actorUserId: UUID?,
        body: String?,
    ) {
        val recipients = accessResolver.effectiveMemberIds(groupId) - excluding
        fanOut(
            recipients = recipients,
            type = type,
            actorUserId = actorUserId,
            actorName = null,
            postId = postId,
            groupId = groupId,
            teamId = null,
            body = body,
        )
    }

    /** 특정 팀의 ACTIVE 팀원 전체에게 (팀 지원 수락·종료). */
    fun notifyTeamMembers(
        teamId: UUID,
        groupId: UUID?,
        postId: UUID?,
        type: NotificationType,
        excluding: Set<UUID>,
        actorUserId: UUID?,
        body: String?,
    ) {
        val recipients =
            teamMemberRepository
                .findAllByTeamIdAndStatus(teamId, TeamMemberStatus.ACTIVE)
                .map { it.userId }
                .toSet() - excluding
        fanOut(
            recipients = recipients,
            type = type,
            actorUserId = actorUserId,
            actorName = null,
            postId = postId,
            groupId = groupId,
            teamId = teamId,
            body = body,
        )
    }

    private fun fanOut(
        recipients: Set<UUID>,
        type: NotificationType,
        actorUserId: UUID?,
        actorName: String?,
        postId: UUID?,
        groupId: UUID?,
        teamId: UUID?,
        body: String?,
    ) {
        if (recipients.isEmpty()) return
        val ordered = recipients.sortedBy { it.toString() }
        val targets =
            if (ordered.size > MAX_FANOUT_PER_EVENT) {
                log().warn(
                    "group notification fan-out capped: type=$type groupId=$groupId teamId=$teamId " +
                        "recipients=${ordered.size} cap=$MAX_FANOUT_PER_EVENT",
                )
                ordered.take(MAX_FANOUT_PER_EVENT)
            } else {
                ordered
            }
        notificationService.createStructuredMany(
            userIds = targets,
            type = type,
            title = GroupNotificationTemplates.titleOf(type, actorName),
            body = body ?: GroupNotificationTemplates.bodyOf(type),
            link = GroupNotificationTemplates.linkOf(type, postId, groupId, teamId),
            actorUserId = actorUserId,
            actorName = actorName,
            postId = postId,
            groupId = groupId,
            teamId = teamId,
        )
    }

    companion object {
        /** 한 이벤트가 만들 수 있는 알림 행 수 상한. 초과분은 버리고 WARN 로그를 남긴다. */
        const val MAX_FANOUT_PER_EVENT = 500
    }
}

/**
 * phase 1 알림 문구 표.
 *
 * 어휘는 설계 §2 만 쓴다: 수색그룹 / 보호자 / 팀장 / 팀원 / 함께 찾기 / 확인할 요청 / 수색 종료 /
 * 우리 팀의 지원 종료. `admin` 은 쓰지 않는다.
 * 좌표·전화번호·차단 사유는 제목과 본문에 넣지 않는다(설계 §9, §16.5).
 *
 * `GROUP_MEMBER_REMOVED` / `GROUP_MEMBER_BLOCKED` 는 actorName 을 **사용하지 않는다**.
 * 누가 왜 그랬는지는 대상자에게 공개하지 않는 것이 설계 §6.3 이다.
 *
 * `JOIN_POLICY_CHANGED` 는 **여기에 없다**. 설계 §8.3 이 요구하는 것은 그룹 활동 기록
 * (`SearchGroupEventType.JOIN_POLICY_CHANGED`)뿐이고 알림 수신자 표에는 그 항목이 없다.
 * NotificationType 상수는 롤링 배포 안전 때문에 남아 있지만 문구도 발행처도 없다.
 */
object GroupNotificationTemplates {
    val PHASE1_TYPES: Set<NotificationType> =
        setOf(
            NotificationType.GROUP_MEMBER_JOINED,
            NotificationType.GROUP_JOIN_REQUESTED,
            NotificationType.GROUP_JOIN_APPROVED,
            NotificationType.GROUP_JOIN_REJECTED,
            NotificationType.GROUP_MEMBER_REMOVED,
            NotificationType.GROUP_MEMBER_BLOCKED,
            NotificationType.TEAM_SUPPORT_REQUESTED,
            NotificationType.TEAM_SUPPORT_ACCEPTED,
            NotificationType.TEAM_SUPPORT_DECLINED,
            NotificationType.TEAM_SUPPORT_ENDED,
            NotificationType.TEAM_MEMBER_REQUESTED,
            NotificationType.TEAM_MEMBER_APPROVED,
            NotificationType.TEAM_MEMBER_REJECTED,
            NotificationType.TEAM_MEMBER_REMOVED,
            NotificationType.TEAM_LEADERSHIP_TRANSFERRED,
            NotificationType.TEAM_ARCHIVED,
            NotificationType.SEARCH_ENDED,
        )

    fun titleOf(
        type: NotificationType,
        actorName: String?,
    ): String =
        when (type) {
            NotificationType.GROUP_MEMBER_JOINED -> "${who(actorName)}님이 함께 찾기에 참여했어요"
            NotificationType.GROUP_JOIN_REQUESTED -> "${who(actorName)}님이 참여를 신청했어요"
            NotificationType.GROUP_JOIN_APPROVED -> "함께 찾기 참여가 승인됐어요"
            NotificationType.GROUP_JOIN_REJECTED -> "함께 찾기 참여 신청이 받아들여지지 않았어요"
            NotificationType.GROUP_MEMBER_REMOVED -> "수색그룹 참여가 종료됐어요"
            NotificationType.GROUP_MEMBER_BLOCKED -> "수색그룹을 더 이상 이용할 수 없어요"
            NotificationType.TEAM_SUPPORT_REQUESTED -> "${who(actorName)}님이 팀 지원을 요청했어요"
            NotificationType.TEAM_SUPPORT_ACCEPTED -> "팀 지원이 시작됐어요"
            NotificationType.TEAM_SUPPORT_DECLINED -> "팀 지원 요청이 받아들여지지 않았어요"
            NotificationType.TEAM_SUPPORT_ENDED -> "우리 팀의 지원이 종료됐어요"
            NotificationType.TEAM_MEMBER_REQUESTED -> "${who(actorName)}님이 팀 참여를 신청했어요"
            NotificationType.TEAM_MEMBER_APPROVED -> "팀 참여가 승인됐어요"
            NotificationType.TEAM_MEMBER_REJECTED -> "팀 참여 신청이 받아들여지지 않았어요"
            NotificationType.TEAM_MEMBER_REMOVED -> "팀 참여가 종료됐어요"
            NotificationType.TEAM_LEADERSHIP_TRANSFERRED -> "팀장이 변경됐어요"
            NotificationType.TEAM_ARCHIVED -> "팀이 보관되었어요"
            NotificationType.SEARCH_ENDED -> "수색 종료 안내"
            else -> "새로운 알림이 있어요"
        }

    fun bodyOf(type: NotificationType): String? =
        when (type) {
            NotificationType.GROUP_MEMBER_JOINED -> "수색그룹에서 함께 찾는 이웃을 확인해 보세요."
            NotificationType.GROUP_JOIN_REQUESTED -> "확인할 요청에서 승인하거나 거절할 수 있어요."
            NotificationType.GROUP_JOIN_APPROVED -> "이제 수색그룹에서 함께 찾을 수 있어요."
            NotificationType.GROUP_JOIN_REJECTED -> "다른 실종 소식에서도 함께 찾을 수 있어요."
            NotificationType.GROUP_MEMBER_REMOVED -> "이 수색그룹의 참여가 종료되었어요."
            NotificationType.GROUP_MEMBER_BLOCKED -> "이 수색그룹에서는 함께 찾기를 이용할 수 없어요."
            NotificationType.TEAM_SUPPORT_REQUESTED -> "확인할 요청에서 수락하거나 거절할 수 있어요."
            NotificationType.TEAM_SUPPORT_ACCEPTED -> "이제 팀원들이 이 수색그룹에서 함께 찾을 수 있어요."
            NotificationType.TEAM_SUPPORT_DECLINED -> "다른 실종 소식에도 팀 지원을 요청할 수 있어요."
            NotificationType.TEAM_SUPPORT_ENDED -> "이 수색그룹에 대한 우리 팀의 지원이 종료되었어요."
            NotificationType.TEAM_MEMBER_REQUESTED -> "확인할 요청에서 승인하거나 거절할 수 있어요."
            NotificationType.TEAM_MEMBER_APPROVED -> "이제 팀원으로 함께 찾을 수 있어요."
            NotificationType.TEAM_MEMBER_REJECTED -> "다른 팀에도 참여를 신청할 수 있어요."
            NotificationType.TEAM_MEMBER_REMOVED -> "이 팀의 참여가 종료되었어요."
            NotificationType.TEAM_LEADERSHIP_TRANSFERRED -> "팀 화면에서 새로운 팀장을 확인해 보세요."
            NotificationType.TEAM_ARCHIVED -> "이 팀의 활동이 종료되어 함께 찾기 지원도 모두 종료되었어요."
            NotificationType.SEARCH_ENDED -> "보호자가 수색 종료를 선택했어요. 이전 기록만 확인할 수 있어요."
            else -> null
        }

    /**
     * 이동 대상.
     * - 승인 대기(요청) 3종 → `/profile` (확인할 요청 허브)
     * - 그룹 접근 권한이 남아 있는 이벤트 → `/lost/{postId}/group`
     * - 권한이 사라진 이벤트(거절·내보내기·차단) → 공개 상세 `/lost/{postId}`
     * - 팀 이벤트 → `/teams/{teamId}`
     */
    fun linkOf(
        type: NotificationType,
        postId: UUID?,
        groupId: UUID?,
        teamId: UUID?,
    ): String? =
        when (type) {
            NotificationType.GROUP_JOIN_REQUESTED,
            NotificationType.TEAM_MEMBER_REQUESTED,
            NotificationType.TEAM_SUPPORT_REQUESTED,
            -> "/profile"

            NotificationType.GROUP_MEMBER_JOINED,
            NotificationType.GROUP_JOIN_APPROVED,
            NotificationType.TEAM_SUPPORT_ACCEPTED,
            NotificationType.SEARCH_ENDED,
            -> postId?.let { "/lost/$it/group" }

            NotificationType.GROUP_JOIN_REJECTED,
            NotificationType.GROUP_MEMBER_REMOVED,
            NotificationType.GROUP_MEMBER_BLOCKED,
            -> postId?.let { "/lost/$it" }

            NotificationType.TEAM_SUPPORT_DECLINED,
            NotificationType.TEAM_SUPPORT_ENDED,
            -> teamId?.let { "/teams/$it" } ?: postId?.let { "/lost/$it/group" }

            NotificationType.TEAM_MEMBER_APPROVED,
            NotificationType.TEAM_MEMBER_REJECTED,
            NotificationType.TEAM_MEMBER_REMOVED,
            NotificationType.TEAM_LEADERSHIP_TRANSFERRED,
            NotificationType.TEAM_ARCHIVED,
            -> teamId?.let { "/teams/$it" }

            else -> null
        }

    private fun who(actorName: String?): String = actorName?.takeIf { it.isNotBlank() } ?: "이웃"
}
