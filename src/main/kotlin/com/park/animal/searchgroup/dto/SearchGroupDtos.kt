package com.park.animal.searchgroup.dto

import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.searchgroup.access.AccessSource
import com.park.animal.searchgroup.access.GroupRole
import com.park.animal.searchgroup.entity.ArchivedReason
import com.park.animal.searchgroup.entity.JoinPolicy
import com.park.animal.searchgroup.entity.SearchGroupEvent
import com.park.animal.searchgroup.entity.SearchGroupEventType
import com.park.animal.searchgroup.entity.SearchGroupMemberStatus
import com.park.animal.searchgroup.entity.SearchGroupStatus
import java.time.LocalDateTime
import java.util.UUID

/**
 * 공개 CTA 버튼이 취해야 할 다음 동작.
 *
 * 이 백엔드는 미인증 요청에 401 을 내지 않는다(F19 — 401 은 게이트웨이 소관).
 * 프런트가 상태코드로 인증 여부를 추론하지 않도록 [LOGIN_REQUIRED] 를 본문에 담는다(계약 §9).
 */
enum class SearchGroupViewerAction {
    /** 자유롭게 참여 — 누르면 바로 활성 참여자가 된다. */
    JOIN_NOW,

    /** 승인 후 참여 — 누르면 PENDING 요청이 생기고 보호자 승인을 기다린다. */
    REQUEST_JOIN,

    /** 보호자이거나 이미 참여 중. */
    ALREADY_JOINED,

    /** 비로그인. 로그인 후 다시 CTA 를 조회한다. */
    LOGIN_REQUIRED,

    /**
     * 참여할 수 없음. **종료된 수색 · 목격 소식 · 차단된 사용자가 모두 이 값 하나를 받는다.**
     * 셋을 구분할 수 있으면 차단 사실이 응답으로 새어나간다(설계 §6.3, 계약 §9).
     */
    UNAVAILABLE,
}

/**
 * 공개 실종 소식의 `함께 찾기` 카드 (계약 §8 #2, 설계 §11).
 *
 * 비로그인 방문자도 받는 응답이므로 **좌표·전화번호·설명 본문을 담지 않는다**(설계 §16.5).
 * 그런 값이 필요하면 공개 `GET /post/{id}` 를 쓴다.
 * **`isBlocked` 같은 필드를 절대 추가하지 않는다** — 차단 여부는 [SearchGroupViewerAction.UNAVAILABLE]
 * 안에 숨어야 하며 비차단 비참여자와 구분 불가해야 한다.
 */
data class SearchGroupCtaResponse(
    val postId: UUID,
    val groupId: UUID,
    val joinPolicy: JoinPolicy,
    val status: SearchGroupStatus,
    val postStatus: MissingAnimalStatus,
    val memberCount: Long,
    val teamCount: Long,
    val viewerAction: SearchGroupViewerAction,
)

/**
 * 수색그룹 상세 (계약 §8 #3·#4·#5 공통 응답).
 *
 * 유효 참여자만 받는 응답이지만 여기에도 좌표·연락처를 담지 않는다 — 지도는 phase 2 의
 * 별도 엔드포인트가 담당하고, 연락처는 공개 게시글 상세의 책임이다.
 *
 * [pendingMemberCount] / [pendingTeamCount] 는 보호자에게만 채워지고 그 외에는 null 이다.
 * `확인할 요청` 은 보호자 전용 화면이다(설계 §7, §10).
 */
data class SearchGroupDetailResponse(
    val groupId: UUID,
    val postId: UUID,
    val postTitle: String,
    val joinPolicy: JoinPolicy,
    val status: SearchGroupStatus,
    val postStatus: MissingAnimalStatus,
    val archivedReason: ArchivedReason?,
    val archivedAt: LocalDateTime?,
    val role: GroupRole,
    val sources: Set<AccessSource>,
    val memberCount: Long,
    val teamCount: Long,
    val pendingMemberCount: Long?,
    val pendingTeamCount: Long?,
    val myMembershipId: UUID?,
    val myMembershipStatus: SearchGroupMemberStatus?,
    val canManage: Boolean,
    val canWrite: Boolean,
)

/**
 * 그룹 활동 기록 한 줄 (계약 §8 #6, 설계 §10 활동 탭 · §20 감사).
 *
 * `detail` 은 상태 전이 요약만 담는다. 좌표·본문·차단 사유는 애초에 저장되지 않는다.
 */
data class SearchGroupEventResponse(
    val id: UUID,
    val type: SearchGroupEventType,
    val actorId: UUID?,
    val targetId: UUID?,
    val detail: String?,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(event: SearchGroupEvent): SearchGroupEventResponse =
            SearchGroupEventResponse(
                id = event.id,
                type = event.type,
                actorId = event.actorId,
                targetId = event.targetId,
                detail = event.detail,
                createdAt = event.createdAt,
            )
    }
}
