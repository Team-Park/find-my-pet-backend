package com.park.animal.notification.entity

/**
 * 알림 종류. `@Enumerated(STRING)` 으로 저장되므로 **상수 이름이 곧 DB 값**이다.
 *
 * 두 가지 불변식을 지킨다.
 * 1. 기존 상수는 삭제·개명하지 않는다. 운영 `notification` 행이 그 문자열을 들고 있고,
 *    코드가 모르는 문자열을 만나면 `list()` 매핑 시점에 목록 **전체**가 500 이 된다.
 * 2. phase 1 상수는 발행 시점이 아니라 **한 번에 전부** 선언한다. 롤링 배포 중 신버전이 만든
 *    문자열을 구버전 replica 가 읽어도 같은 이유로 전체가 500 이 되기 때문이다.
 *    그래서 `JOIN_POLICY_CHANGED` 처럼 phase 1 에서 발행하지 않는 상수도 여기 남는다.
 */
enum class NotificationType {
    /** 내 게시글에 목격 제보가 등록됨 */
    SIGHTING_REGISTERED,

    /** 내가 즐겨찾기한 게시글의 상태(SEARCHING/FOUND/SEEN)가 변경됨 */
    BOOKMARK_STATUS_CHANGED,

    /** 내가 구독한 지역에 신규 유기동물 등록됨 */
    ABANDONED_NEW_IN_REGION,

    // --- 함께 찾기 phase 1 (수색그룹) ---
    GROUP_MEMBER_JOINED,
    GROUP_JOIN_REQUESTED,
    GROUP_JOIN_APPROVED,
    GROUP_JOIN_REJECTED,
    GROUP_MEMBER_REMOVED,
    GROUP_MEMBER_BLOCKED,

    /** 선언만 한다. 설계 §8.3 은 정책 변경을 활동 기록으로만 남기라고 요구하므로 발행처가 없다. */
    JOIN_POLICY_CHANGED,

    // --- 함께 찾기 phase 1 (팀) ---
    TEAM_SUPPORT_REQUESTED,
    TEAM_SUPPORT_ACCEPTED,
    TEAM_SUPPORT_DECLINED,
    TEAM_SUPPORT_ENDED,
    TEAM_MEMBER_REQUESTED,
    TEAM_MEMBER_APPROVED,
    TEAM_MEMBER_REJECTED,
    TEAM_MEMBER_REMOVED,
    TEAM_LEADERSHIP_TRANSFERRED,

    /** 팀장이 팀을 보관 처리 — 그 팀의 모든 지원 연결이 함께 종료된다 (설계 §6.4, Task 8). */
    TEAM_ARCHIVED,

    // --- 함께 찾기 phase 1 (생명주기) ---
    SEARCH_ENDED,

    // --- 선언만, phase 2/3 에서 발행 ---
    SIGHTING_CREATED,
    CHAT_MENTIONED,
    GROUP_SYSTEM_EVENT,
}
