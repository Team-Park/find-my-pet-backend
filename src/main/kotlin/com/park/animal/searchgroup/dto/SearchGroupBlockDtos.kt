package com.park.animal.searchgroup.dto

import com.park.animal.searchgroup.entity.SearchGroupUserBlock
import java.time.LocalDateTime
import java.util.UUID

/**
 * 차단 요청. [reason] 은 운영 감사용이며 보호자 전용 목록 조회에서만 되돌아온다.
 * 알림·활동 기록·다른 응답에는 절대 싣지 않는다(설계 §6.3, §16.5).
 */
data class BlockSearchGroupUserRequest(
    val targetUserId: UUID,
    val reason: String? = null,
)

/**
 * 보호자 전용 차단 항목. 이 DTO 는 `GET /search-groups/{groupId}/blocks` 응답에만 쓴다.
 *
 * [userName] 은 멤버십 행(`search_group_member.user_name`)에서 가져온 표시 이름이다.
 * 차단 대상이 직접 참여한 적이 없으면 null 이며, 그 경우 프론트는 id 대신 "알 수 없음" 을 보여준다.
 */
data class SearchGroupBlockResponse(
    val blockId: UUID,
    val groupId: UUID,
    val userId: UUID,
    val userName: String?,
    val reason: String?,
    val blockedAt: LocalDateTime,
    val unblockedAt: LocalDateTime?,
) {
    companion object {
        fun of(
            b: SearchGroupUserBlock,
            userName: String?,
        ): SearchGroupBlockResponse =
            SearchGroupBlockResponse(
                blockId = b.id,
                groupId = b.groupId,
                userId = b.userId,
                userName = userName,
                reason = b.reason,
                blockedAt = b.blockedAt,
                unblockedAt = b.unblockedAt,
            )
    }
}
