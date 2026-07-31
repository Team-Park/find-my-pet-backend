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

    @Test
    fun `공고기간이 0일이어도 법정 최소기간까지는 살아있다`() {
        val today = NoticePeriod.today(crossover) // 20260728

        // 실제 사례 413582202600529 — 7/26 발견인데 공고종료일도 7/26.
        // notice_edt 만 보면 이미 지났지만, 실효 종료일은 20260726+7 = 20260802 다.
        assertFalse(
            NoticePeriod.isOver("20260726", today, noticeSdt = "20260726"),
            "공고기간 0일짜리를 그대로 믿으면 어제 구조된 아이가 오늘 사라진다",
        )
        assertEquals("20260802", NoticePeriod.effectiveEdt("20260726", "20260726"))
    }

    @Test
    fun `그 아이도 결국은 만료된다 - 영원히 남으면 그것도 버그다`() {
        // "기간이 짧으면 제외" 로 처리하면 공고기간은 레코드 속성이라 날짜가 지나도 안 변해서
        // 4.6% 가 영원히 쌓인다. 실효 종료일 방식은 최소 노출만 보장하고 결국 만료시킨다.
        assertFalse(NoticePeriod.isOver("20260726", "20260802", noticeSdt = "20260726"), "실효 종료일 당일은 아직 아니다")
        assertTrue(NoticePeriod.isOver("20260726", "20260803", noticeSdt = "20260726"), "7일이 지나면 만료돼야 한다")
        assertTrue(NoticePeriod.isOver("20260726", "20261231", noticeSdt = "20260726"), "반년이 지나도 안 내려가면 안 된다")
    }

    @Test
    fun `정상 공고는 실효 종료일이 notice_edt 그대로다`() {
        assertEquals("20260727", NoticePeriod.effectiveEdt("20260717", "20260727"), "10일짜리는 하한에 안 걸린다")
        assertEquals("20260722", NoticePeriod.effectiveEdt("20260715", "20260722"), "7일짜리도 하한과 같다")
    }

    @Test
    fun `notice_sdt 로 교차 검증할 수 없으면 notice_edt 만으로 판정한다`() {
        val today = NoticePeriod.today(crossover)
        // 시작일을 모르면 기간을 잴 수 없다. 그렇다고 전부 살려두면 만료 처리 자체가 무의미해지므로
        // 이때는 종전대로 notice_edt 단독 판정으로 돌아간다.
        assertTrue(NoticePeriod.isOver("20260726", today, noticeSdt = null))
        assertTrue(NoticePeriod.isOver("20260726", today, noticeSdt = "2026-07-16"))
    }

    @Test
    fun `발견일이 공고시작일보다 늦으면 발견일 기준 7일을 보장한다`() {
        assertEquals(
            "20260730",
            NoticePeriod.effectiveEdt("20260722", "20260722", happenDt = "20260723"),
        )
        assertFalse(NoticePeriod.isOver("20260722", "20260730", "20260722", "20260723"))
        assertTrue(NoticePeriod.isOver("20260722", "20260731", "20260722", "20260723"))
    }

    @Test
    fun `달력에 없는 종료일은 판정 불가라 닫지 않는다`() {
        assertEquals(null, NoticePeriod.effectiveEdt("20260220", "20260230", "20260220"))
        assertFalse(NoticePeriod.isOver("20260230", "20260301", "20260220", "20260220"))
    }

    @Test
    fun `날짜 상한에서도 예외로 동기화를 깨뜨리지 않는다`() {
        assertEquals(null, NoticePeriod.effectiveEdt("99991231", "99991231", "99991231"))
        assertFalse(NoticePeriod.isOver("99991231", "99991231", "99991231", "99991231"))
    }
}
