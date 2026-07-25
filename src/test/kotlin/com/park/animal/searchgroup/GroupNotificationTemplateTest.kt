package com.park.animal.searchgroup

import com.park.animal.notification.entity.NotificationType
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 문구 테이블 고정 (설계 §2 어휘 · §9 민감정보 금지 · §6.3 행위자 비공개).
 */
class GroupNotificationTemplateTest {
    private val postId = UUID.randomUUID()
    private val groupId = UUID.randomUUID()
    private val teamId = UUID.randomUUID()

    @Test
    fun `phase 1 모든 타입에 제목과 본문이 있다`() {
        GroupNotificationTemplates.PHASE1_TYPES.forEach { type ->
            val title = GroupNotificationTemplates.titleOf(type, "김보호")
            val body = GroupNotificationTemplates.bodyOf(type)
            assertTrue(title.isNotBlank(), "$type 제목 누락")
            assertTrue(body != null && body.isNotBlank(), "$type 본문 누락")
        }
    }

    @Test
    fun `phase 1 타입 집합은 16종이다`() {
        assertEquals(17, GroupNotificationTemplates.PHASE1_TYPES.size)
        assertTrue(NotificationType.SIGHTING_CREATED !in GroupNotificationTemplates.PHASE1_TYPES)
        assertTrue(NotificationType.CHAT_MENTIONED !in GroupNotificationTemplates.PHASE1_TYPES)
        assertTrue(NotificationType.GROUP_SYSTEM_EVENT !in GroupNotificationTemplates.PHASE1_TYPES)
    }

    @Test
    fun `참여 정책 변경은 알림을 발행하지 않는다`() {
        // 설계 §8.3 은 "정책 변경은 그룹 활동 기록에 남긴다" 만 요구한다. 알림 수신자 표에 이 항목이 없다.
        // 상수는 롤링 배포 안전을 위해 NotificationType 에 남아 있지만 발행 경로는 0개다.
        assertTrue(NotificationType.JOIN_POLICY_CHANGED !in GroupNotificationTemplates.PHASE1_TYPES)
        assertNull(GroupNotificationTemplates.bodyOf(NotificationType.JOIN_POLICY_CHANGED))
        assertNull(GroupNotificationTemplates.linkOf(NotificationType.JOIN_POLICY_CHANGED, postId, groupId, null))
    }

    @Test
    fun `내보내기와 차단 문구는 행위자 이름에 영향받지 않는다`() {
        listOf(NotificationType.GROUP_MEMBER_REMOVED, NotificationType.GROUP_MEMBER_BLOCKED).forEach { type ->
            val withActor = GroupNotificationTemplates.titleOf(type, "김보호")
            val withOther = GroupNotificationTemplates.titleOf(type, "이참여")
            val withNull = GroupNotificationTemplates.titleOf(type, null)
            assertEquals(withActor, withOther, "$type 은 행위자를 노출하면 안 된다")
            assertEquals(withActor, withNull, "$type 은 행위자를 노출하면 안 된다")
            assertTrue(!withActor.contains("김보호"))
        }
    }

    @Test
    fun `참여 관련 문구는 행위자 이름을 포함한다`() {
        assertTrue(GroupNotificationTemplates.titleOf(NotificationType.GROUP_MEMBER_JOINED, "김보호").contains("김보호"))
        assertTrue(GroupNotificationTemplates.titleOf(NotificationType.GROUP_JOIN_REQUESTED, "김보호").contains("김보호"))
    }

    @Test
    fun `문구에 금지어와 숫자가 없다`() {
        val forbidden = listOf("admin", "Admin", "ADMIN", "관리자", "좌표", "위도", "경도", "전화", "휴대폰", "사유", "차단")
        val digits = Regex("\\d")
        GroupNotificationTemplates.PHASE1_TYPES.forEach { type ->
            val text = GroupNotificationTemplates.titleOf(type, "김보호") + " " + GroupNotificationTemplates.bodyOf(type)
            forbidden.forEach { word ->
                assertTrue(!text.contains(word), "$type 문구에 금지어 '$word' 가 있다: $text")
            }
            assertTrue(!digits.containsMatchIn(text), "$type 문구에 숫자가 있다(전화번호·좌표 유출 위험): $text")
        }
    }

    @Test
    fun `링크는 그룹 팀 승인대기 규칙을 따른다`() {
        assertEquals(
            "/lost/$postId/group",
            GroupNotificationTemplates.linkOf(NotificationType.GROUP_MEMBER_JOINED, postId, groupId, null),
        )
        assertEquals(
            "/lost/$postId",
            GroupNotificationTemplates.linkOf(NotificationType.GROUP_MEMBER_BLOCKED, postId, groupId, null),
        )
        assertEquals(
            "/profile",
            GroupNotificationTemplates.linkOf(NotificationType.GROUP_JOIN_REQUESTED, postId, groupId, null),
        )
        assertEquals(
            "/teams/$teamId",
            GroupNotificationTemplates.linkOf(NotificationType.TEAM_MEMBER_APPROVED, null, null, teamId),
        )
        assertNull(GroupNotificationTemplates.linkOf(NotificationType.SIGHTING_REGISTERED, postId, groupId, teamId))
    }
}
