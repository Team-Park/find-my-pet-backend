package com.park.animal.share

import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 공유 집계는 저장소가 없어 DB 없이 검증한다.
 *
 * 여기서 지키려는 것은 두 가지다. (1) 라벨 카디널리티가 enum 으로 고정되는 것 — 프론트가 임의
 * 문자열을 보내도 새 시계열이 생기면 안 된다. (2) 라벨에 id 가 섞이지 않는 것.
 */
class ShareEventControllerTest {
    private val registry = SimpleMeterRegistry()
    private val controller = ShareEventController(registry)

    private fun count(
        channel: String,
        contentType: String,
    ): Double =
        registry
            .find(ShareEventController.SHARE_METRIC)
            .tag("channel", channel)
            .tag("content_type", contentType)
            .counter()
            ?.count() ?: 0.0

    @Test
    fun `채널과 대상별로 카운터가 올라간다`() {
        controller.record(RecordShareEventRequest("KAKAO", "LOST"))
        controller.record(RecordShareEventRequest("KAKAO", "LOST"))
        controller.record(RecordShareEventRequest("LINK_COPY", "ABANDONED"))

        assertEquals(2.0, count("KAKAO", "LOST"))
        assertEquals(1.0, count("LINK_COPY", "ABANDONED"))
        assertEquals(0.0, count("NATIVE", "LOST"))
    }

    @Test
    fun `모르는 채널은 400 이고 새 시계열을 만들지 않는다`() {
        val before = registry.find(ShareEventController.SHARE_METRIC).counters().size

        assertFailsWith<BusinessException> {
            controller.record(RecordShareEventRequest("TWITTER", "LOST"))
        }.also { assertEquals(ErrorCode.MISSING_PARAMETER, it.errorCode) }

        assertEquals(
            before,
            registry.find(ShareEventController.SHARE_METRIC).counters().size,
            "검증 실패한 요청이 시계열을 늘리면 카디널리티 상한이 무너진다",
        )
    }

    @Test
    fun `모르는 대상 종류도 400 이다`() {
        assertFailsWith<BusinessException> {
            controller.record(RecordShareEventRequest("KAKAO", "REVIEW"))
        }.also { assertEquals(ErrorCode.MISSING_PARAMETER, it.errorCode) }
    }

    @Test
    fun `대소문자가 다르면 거절한다 - 소문자 허용이 시계열을 두 배로 만든다`() {
        assertFailsWith<BusinessException> {
            controller.record(RecordShareEventRequest("kakao", "LOST"))
        }
    }

    @Test
    fun `시계열은 채널x대상 조합으로만 늘어나고 라벨에 id 가 없다`() {
        ShareChannel.entries.forEach { channel ->
            ShareContentType.entries.forEach { type ->
                controller.record(RecordShareEventRequest(channel.name, type.name))
            }
        }

        val counters = registry.find(ShareEventController.SHARE_METRIC).counters()
        assertEquals(
            ShareChannel.entries.size * ShareContentType.entries.size,
            counters.size,
            "가능한 조합 수를 넘는 시계열이 생기면 라벨에 가변 값이 섞인 것이다",
        )
        counters.forEach { counter ->
            val tagKeys = counter.id.tags.map { it.key }.toSet()
            assertEquals(
                setOf("channel", "content_type"),
                tagKeys,
                "라벨은 channel/content_type 둘뿐이어야 한다 — id 가 들어가면 카디널리티가 터진다",
            )
            counter.id.tags.forEach { tag ->
                assertTrue(
                    tag.value.none { it.isDigit() },
                    "라벨 값에 숫자가 있으면 id 가 섞였을 가능성이 크다: ${tag.key}=${tag.value}",
                )
            }
        }
    }
}
