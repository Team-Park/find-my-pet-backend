package com.park.animal.searchgroup.entity

/** 수색그룹 참여 정책. 기본은 자유롭게 참여(OPEN). */
enum class JoinPolicy { OPEN, APPROVAL_REQUIRED }

/** 수색그룹 생명주기. 종료된 그룹은 ARCHIVED 이며 재활성화 API 는 없다(설계 7). */
enum class SearchGroupStatus { ACTIVE, ARCHIVED }

/** 보관 사유. SEEN 은 그룹을 보관하지 않으므로 여기에 없다. */
enum class ArchivedReason { FOUND, POST_DELETED }

/**
 * 직접 참여 멤버십 상태. soft-delete 를 쓰지 않고 이 값으로만 생명주기를 표현한다.
 * 재가입은 LEFT/REMOVED/REJECTED 행의 ACTIVE 전이다 — 새 행을 만들지 않는다(F15).
 */
enum class SearchGroupMemberStatus { PENDING, ACTIVE, REJECTED, LEFT, REMOVED }

/** 팀 지원 연결 상태. 어느 쪽이 먼저 요청했는지에 따라 대기 주체가 갈린다. */
enum class SearchGroupTeamStatus {
    PENDING_GROUP_APPROVAL,
    PENDING_TEAM_APPROVAL,
    ACTIVE,
    DECLINED,
    WITHDRAWN,
    REMOVED,
}

/** 감사 이벤트 종류(설계 20). 본문·좌표는 절대 남기지 않고 행위자/대상 id 와 상태만 기록한다. */
enum class SearchGroupEventType {
    GROUP_OPENED,
    JOIN_POLICY_CHANGED,
    MEMBER_JOINED,
    MEMBER_REQUESTED,
    MEMBER_APPROVED,
    MEMBER_REJECTED,
    MEMBER_LEFT,
    MEMBER_REMOVED,
    USER_BLOCKED,
    USER_UNBLOCKED,
    TEAM_SUPPORT_REQUESTED,
    TEAM_SUPPORT_ACCEPTED,
    TEAM_SUPPORT_DECLINED,
    TEAM_SUPPORT_WITHDRAWN,
    TEAM_SUPPORT_REMOVED,
    SEARCH_ENDED,
    GROUP_ARCHIVED_BY_POST_DELETE,
}
