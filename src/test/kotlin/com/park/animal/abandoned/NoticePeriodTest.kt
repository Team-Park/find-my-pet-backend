package com.park.animal.abandoned

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 공고기간 판정의 **시간대**를 못박는다.
 *
 * `notice_edt` 는 국내 지자체가 KST 로 산정한 날짜다. 그런데 프로덕션 컨테이너에는 TZ 도
 * `-Duser.timezone` 도 없어 JVM 기본이 UTC 다. 시스템 기본으로 판정하면 KST 00:00~09:00 구간에서
 * 서버가 하루 전 날짜로 판정한다 — 공고가 끝난 아이가 아홉 시간 더 "진행 중" 으로 남거나,
 * 자정 직후 갱신된 공고를 만료로 본다. 프론트는 KST 로 보정하므로 두 레포가 어긋나기도 한다.
 *
 * 이 테스트가 없으면 KST 개발 머신에서는 `LocalDate.now()` 와 `LocalDate.now(KST)` 가 같은 값을 내
 * 회귀를 잡을 수 없다. 그래서 시각을 고정해 **UTC 와 KST 의 날짜가 갈리는 구간**을 직접 겨눈다.
 */
class NoticePeriodTest {
    /** UTC 2026-07-27 23:30 = KST 2026-07-28 08:30. 두 시간대의 날짜가 갈리는 구간. */
    private val crossover: Clock = Clock.fixed(Instant.parse("2026-07-27T23:30:00Z"), ZoneOffset.UTC)

    @Test
    fun `자정 넘긴 KST 구간에서 KST 날짜를 낸다 - 시스템 기본을 쓰면 하루 어긋난다`() {
        assertEquals(
            "20260728",
            NoticePeriod.today(crossover),
            "UTC 기준으로 판정하면 20260727 이 나온다 — notice_edt 는 KST 날짜다",
        )
    }

    @Test
    fun `판정 시간대는 Asia Seoul 고정이다`() {
        assertEquals(ZoneId.of("Asia/Seoul"), NoticePeriod.ZONE)
    }

    @Test
    fun `그 구간에서 공고 만료 판정도 KST 를 따른다`() {
        val today = NoticePeriod.today(crossover)
        // KST 로는 이미 28일이므로 27일자 공고는 끝났다. UTC 로 봤다면 아직 진행중으로 잘못 판정한다.
        assertTrue(NoticePeriod.isOver("20260727", today), "KST 기준 어제 공고가 만료로 안 잡혔다")
        assertFalse(NoticePeriod.isOver("20260728", today), "당일 공고는 아직 만료가 아니다")
    }

    @Test
    fun `판정 불가는 만료로 보지 않는다 - 살아있는 아이를 숨기지 않기 위해`() {
        val today = NoticePeriod.today(crossover)
        assertFalse(NoticePeriod.isOver(null, today))
        assertFalse(NoticePeriod.isOver("", today))
        assertFalse(NoticePeriod.isOver("2026-07-27", today), "하이픈 포함은 형식 불량이다")
        assertFalse(NoticePeriod.isOver("202607", today))
        assertFalse(NoticePeriod.isOver("20260727x", today))
    }
}
