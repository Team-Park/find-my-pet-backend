package com.park.animal.notification

import com.park.animal.notification.entity.Notification
import com.park.animal.notification.repository.NotificationRepository
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Sort
import org.springframework.data.repository.query.parser.PartTree
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F20 회귀 방지.
 *
 * notification.created_at 은 V7 에서 초 정밀도 TIMESTAMP 로 만들어졌고 목록은 offset
 * 페이지네이션이다. 정렬 키가 createdAt 하나뿐이면 같은 초에 들어온 알림들의 순서가
 * 페이지마다 달라져 경계에서 중복/누락이 난다. 함께 찾기는 한 액션에서 그룹 전체에
 * 알림을 fanout 하므로(설계 9) 동일 초 다건이 상시 발생한다.
 *
 * DB 없이 파생 쿼리 이름을 파싱해 tiebreaker(id DESC)가 살아 있는지 고정한다.
 * 개명된 이름은 Task 5·10 을 포함한 모든 하류 코드가 그대로 호출해야 하는 계약이므로
 * 옛 이름이 남아 있지 않은 것까지 함께 확인한다.
 */
class NotificationOrderingTest {
    private val queryMethodName = "findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc"
    private val legacyMethodName = "findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc"

    @Test
    fun `알림 목록 파생 쿼리는 createdAt DESC 뒤에 id DESC tiebreaker 를 갖는다`() {
        val tree = PartTree(queryMethodName, Notification::class.java)

        val orders = tree.sort.toList()

        assertEquals(listOf("createdAt", "id"), orders.map { it.property })
        assertTrue(orders.all { it.direction == Sort.Direction.DESC }, "두 정렬 키 모두 DESC 여야 한다")
    }

    @Test
    fun `NotificationRepository 가 그 파생 쿼리를 선언한다`() {
        val declared = NotificationRepository::class.java.methods.map { it.name }

        assertTrue(
            queryMethodName in declared,
            "리포지토리 메서드명이 바뀌면 tiebreaker 검증이 무의미해진다. 선언된 메서드: $declared",
        )
        assertFalse(
            legacyMethodName in declared,
            "tiebreaker 없는 옛 이름이 남아 있으면 하류 태스크가 그걸 계속 호출한다. 선언된 메서드: $declared",
        )
    }
}
