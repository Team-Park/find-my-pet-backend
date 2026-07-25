package com.park.animal.notification.entity

/**
 * 설계 §9 "함께 찾기" 신규 상수는 실제로 발행하는 태스크(4~9)보다 먼저, phase 1 착수 시점에
 * 전량 한 번에 선언한다. 롤링 배포 중 구버전 replica 가 새 문자열이 섞인 notification 행을
 * 읽으면 `NotificationType.valueOf` 가 던져 알림 목록 조회 전체가 500 이 되기 때문이다
 * (한 태스크씩 상수를 추가하면 그 창이 태스크 수만큼 반복된다).
 *
 * [SIGHTING_CREATED] / [CHAT_MENTIONED] / [GROUP_SYSTEM_EVENT] 는 이번 phase 1 에서
 * enum 값만 선언하고 실제로 발행하지 않는다(각각 phase 2 지도, phase 3 채팅 대상).
 */
enum class NotificationType {
    /** 내 게시글에 목격 제보가 등록됨 (기존 — 함께 찾기 그룹과 무관) */
    SIGHTING_REGISTERED,

    /** 내가 즐겨찾기한 게시글의 상태(SEARCHING/FOUND/SEEN)가 변경됨 */
    BOOKMARK_STATUS_CHANGED,

    /** 내가 구독한 지역에 신규 유기동물 등록됨 */
    ABANDONED_NEW_IN_REGION,

    /** 자유 참여로 직접 멤버가 생김 → 보호자 */
    GROUP_MEMBER_JOINED,

    /** 승인 후 참여 신청이 들어옴 → 보호자 */
    GROUP_JOIN_REQUESTED,

    /** 참여 신청이 승인됨 → 신청자 */
    GROUP_JOIN_APPROVED,

    /** 참여 신청이 거절됨 → 신청자 */
    GROUP_JOIN_REJECTED,

    /** 내보내기 또는 차단됨 → 대상 사용자 */
    GROUP_MEMBER_REMOVED,

    /** 사용자가 차단됨 → 대상 사용자 */
    GROUP_MEMBER_BLOCKED,

    /** 팀 지원 연결 요청이 들어옴 → 처리할 보호자 또는 팀장 */
    TEAM_SUPPORT_REQUESTED,

    /** 팀 지원 연결이 수락됨 → 요청 시작자·새로 권한을 얻은 활성 팀원 */
    TEAM_SUPPORT_ACCEPTED,

    /** 팀 지원 연결이 거절됨 → 요청 시작자 */
    TEAM_SUPPORT_DECLINED,

    /** 팀 지원 연결이 종료됨 → 보호자·해당 팀의 활성 팀원 */
    TEAM_SUPPORT_ENDED,

    /** 목격 제보 등록 → 제보자를 제외한 그룹 유효 참여자 (phase 2, enum 만 선언) */
    SIGHTING_CREATED,

    /** 수색 종료 → 종료한 보호자를 제외한 그룹 유효 참여자 */
    SEARCH_ENDED,

    /** 채팅 호명 → 호명된 사용자 (phase 3, enum 만 선언) */
    CHAT_MENTIONED,

    /** 그룹 시스템 이벤트 (phase 3, enum 만 선언) */
    GROUP_SYSTEM_EVENT,
}
