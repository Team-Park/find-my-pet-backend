package com.park.animal.searchgroup.dto

import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupMember
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import java.time.LocalDateTime
import java.util.UUID

/**
 * 직접 참여 멤버십 1건. 차단 여부는 담지 않는다 — 차단 사실은 응답으로 알 수 없어야 한다(설계 §16.5).
 */
data class SearchGroupMembershipResponse(
    val membershipId: UUID,
    val groupId: UUID,
    val userId: UUID,
    val userName: String?,
    val status: SearchGroupMemberStatus,
    val requestedAt: LocalDateTime?,
    val joinedAt: LocalDateTime?,
    val decidedAt: LocalDateTime?,
) {
    companion object {
        fun from(m: SearchGroupMember): SearchGroupMembershipResponse =
            SearchGroupMembershipResponse(
                membershipId = m.id,
                groupId = m.groupId,
                userId = m.userId,
                userName = m.userName,
                status = m.status,
                requestedAt = m.requestedAt,
                joinedAt = m.joinedAt,
                decidedAt = m.decidedAt,
            )
    }
}

/**
 * `함께 찾기` 버튼의 응답. [approvalPending] 이 true 면 프론트는 "확인할 요청 대기" 문구를 띄운다.
 */
data class JoinSearchGroupResponse(
    val membership: SearchGroupMembershipResponse,
    val joinPolicy: JoinPolicy,
    val approvalPending: Boolean,
) {
    companion object {
        fun of(
            member: SearchGroupMember,
            joinPolicy: JoinPolicy,
        ): JoinSearchGroupResponse =
            JoinSearchGroupResponse(
                membership = SearchGroupMembershipResponse.from(member),
                joinPolicy = joinPolicy,
                approvalPending = member.status == SearchGroupMemberStatus.PENDING,
            )
    }
}

/**
 * 참여 정책 변경 요청.
 *
 * 필드 누락이나 알 수 없는 enum 문자열은 Jackson 이 역직렬화 단계에서 거절하므로
 * `HttpMessageNotReadableException` 이 되고, Task 1 의 핸들러가 이를 **400 MISSING_PARAMETER** 로
 * 매핑한다(그 전에는 500 이었다, F9). `INVALID_COLLABORATION_INPUT` 은 서비스 계층의 명시적
 * 값 검증 실패(팀 이름 길이 등)에만 쓰는 코드이므로 여기서는 쓰지 않는다.
 */
data class UpdateJoinPolicyRequest(
    val joinPolicy: JoinPolicy,
)
