package com.park.animal.searchgroup.access

import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import java.util.UUID

/**
 * 수색그룹에서의 역할. 설계 §2 에 따라 제품 역할에 `admin` 을 두지 않는다.
 * 서비스 전체 관리자 권한(`Passport.role`)은 이 판정에 절대 개입하지 않는다.
 */
enum class GroupRole {
    /** 실종 소식 보호자 (`Post.authorId` 에서 파생) */
    OWNER,

    /** 직접 참여 또는 팀 지원으로 권한을 얻은 사용자 */
    PARTICIPANT,

    /** 권한 없음 */
    NONE,
}

/** 권한이 어디서 왔는지. 한 사용자가 여러 출처를 동시에 가질 수 있다(설계 §6.6). */
enum class AccessSource {
    OWNER,
    DIRECT,
    TEAM,
}

/**
 * 한 번의 조회로 확정된 "이 사용자가 이 수색그룹에 대해 무엇을 할 수 있는가".
 *
 * 프런트에 그대로 노출하지 않는다 — [blocked] 는 응답 DTO 에 담지 않으며(계약 §9),
 * 차단 사용자는 아카이브·권한없음과 구분 불가한 응답을 받아야 한다.
 */
data class GroupAccess(
    val groupId: UUID,
    val postId: UUID,
    val ownerUserId: UUID,
    val viewerId: UUID?,
    val groupStatus: SearchGroupStatus,
    val joinPolicy: JoinPolicy,
    val postDeleted: Boolean,
    val postStatus: MissingAnimalStatus,
    val role: GroupRole,
    val sources: Set<AccessSource>,
    val teamCount: Int,
    val directMembershipId: UUID?,
    val directMembershipStatus: SearchGroupMemberStatus?,
    val blocked: Boolean,
) {
    val isOwner: Boolean get() = role == GroupRole.OWNER

    /** soft-delete 된 실종 소식의 그룹은 존재하지 않는 것으로 취급한다(설계 §14.2). */
    val visible: Boolean get() = !postDeleted

    val canRead: Boolean get() = visible && !blocked && role != GroupRole.NONE

    val canWrite: Boolean get() = canRead && groupStatus == SearchGroupStatus.ACTIVE

    val canManage: Boolean get() = isOwner && visible && groupStatus == SearchGroupStatus.ACTIVE

    val canJoin: Boolean
        get() =
            visible && !blocked && groupStatus == SearchGroupStatus.ACTIVE &&
                postStatus == MissingAnimalStatus.SEARCHING && role == GroupRole.NONE
}
