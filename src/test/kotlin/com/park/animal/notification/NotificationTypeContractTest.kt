package com.park.animal.notification

import com.park.animal.notification.entity.NotificationType
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * NotificationType 은 `@Enumerated(STRING)` 으로 저장된다. 운영 notification 행이 들고 있는
 * 문자열을 코드가 모르면 목록 조회 전체가 500 이 된다(구버전 replica 도 동일).
 * 그래서 (a) legacy 3종은 절대 삭제·개명하지 않고, (b) phase 1 상수는 한 번에 전부 배포한다.
 *
 * `JOIN_POLICY_CHANGED` 는 phase 1 에서 **발행하지 않지만**(설계 §8.3 은 활동 기록만 요구)
 * 상수 자체는 여기에 남겨 둔다 — 롤링 배포 안전성은 발행 여부가 아니라 선언 여부에 달려 있다.
 */
class NotificationTypeContractTest {
    private val names = NotificationType.values().map { it.name }.toSet()

    @Test
    fun `legacy 3종 상수는 이름 그대로 남아 있어야 한다`() {
        listOf(
            "SIGHTING_REGISTERED",
            "BOOKMARK_STATUS_CHANGED",
            "ABANDONED_NEW_IN_REGION",
        ).forEach { legacy ->
            assertTrue(legacy in names, "운영 행이 들고 있는 legacy 문자열 $legacy 이 사라지면 알림 목록 전체가 500 이 된다")
        }
    }

    @Test
    fun `함께 찾기 phase 1 상수는 한 번에 전부 선언돼 있어야 한다`() {
        listOf(
            "GROUP_MEMBER_JOINED", "GROUP_JOIN_REQUESTED", "GROUP_JOIN_APPROVED", "GROUP_JOIN_REJECTED",
            "GROUP_MEMBER_REMOVED", "GROUP_MEMBER_BLOCKED", "JOIN_POLICY_CHANGED",
            "TEAM_SUPPORT_REQUESTED", "TEAM_SUPPORT_ACCEPTED", "TEAM_SUPPORT_DECLINED", "TEAM_SUPPORT_ENDED",
            "TEAM_MEMBER_REQUESTED", "TEAM_MEMBER_APPROVED", "TEAM_MEMBER_REJECTED", "TEAM_MEMBER_REMOVED",
            "TEAM_LEADERSHIP_TRANSFERRED",
            "SEARCH_ENDED",
            "SIGHTING_CREATED", "CHAT_MENTIONED", "GROUP_SYSTEM_EVENT",
        ).forEach { added ->
            assertTrue(added in names, "롤링 배포 중 구버전 replica 가 모르는 문자열을 만나지 않도록 $added 도 phase 1 에 함께 선언한다")
        }
    }
}
