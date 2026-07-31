package com.park.animal.abandoned

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 공공데이터 공고기간(`notice_sdt` ~ `notice_edt`) 판정.
 *
 * 상류(data.go.kr)의 `process_state` 는 실제로 갱신되지 않아 발견 후 100일이 지난 공고도
 * "보호중" 으로 내려온다. 그래서 저장된 공고 종료일에 공고 시작일·발견일의 7일 하한을 반영한
 * 실효 종료일을 서버의 단일 판정 기준으로 사용한다.
 *
 * 모든 원본 날짜는 엄격한 Gregorian `YYYYMMDD` 파싱을 통과한 뒤 비교한다.
 */
object NoticePeriod {
    private val YYYYMMDD = Regex("^\\d{8}$")

    /**
     * 판정 기준 시간대를 **KST 로 고정**한다.
     *
     * `notice_edt` 는 국내 지자체가 KST 로 산정한 날짜다. 그런데 컨테이너에는 TZ 도
     * `-Duser.timezone` 도 없어 JVM 기본이 UTC 다. `LocalDate.now()` 를 그대로 쓰면 KST 기준
     * 00:00~09:00 사이에 서버가 어제 날짜로 판정한다 — 공고가 끝난 아이가 9시간 더 "진행 중" 으로
     * 남거나, 반대로 자정 직후 갱신된 공고를 만료로 본다.
     *
     * 프론트도 KST 로 보정하고 있어(`find-my-pet-frontend/src/lib/abandonment.ts`), 여기를
     * 시스템 기본에 맡기면 두 레포의 판정이 하루 어긋난다. 배포 환경 설정에 의존하지 않도록
     * 코드에서 못박는다.
     */
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    /**
     * [clock] 을 받는 이유는 테스트 때문만이 아니다. 인자가 없으면 이 함수가 KST 를 쓰는지
     * 시스템 기본을 쓰는지 **KST 머신에서는 구분할 수 없어** 회귀를 잡지 못한다.
     * 시각을 고정할 수 있어야 "UTC 서버에서 하루 어긋나는가" 를 테스트로 못박을 수 있다.
     */
    fun today(clock: Clock = Clock.systemUTC()): String =
        LocalDate.now(clock.withZone(ZONE)).format(DateTimeFormatter.BASIC_ISO_DATE)

    /**
     * 법정 최소 공고기간(일). 동물보호법상 보호조치 사실을 7일 이상 공고해야 한다.
     * 공고일부터 10일이 지나도 소유자를 알 수 없을 때의 지자체 소유권 취득 조건과는 별개다.
     *
     * 상류가 이보다 짧은 기간을 주면 **데이터가 틀린 것이지 공고가 진짜 끝난 게 아니다.**
     */
    private const val MIN_NOTICE_DAYS = 7L

    /**
     * 공고기간이 [today] 기준으로 이미 끝났는지.
     *
     * ## 판정 불가는 만료로 보지 않는다
     *
     * 값이 없거나 `YYYYMMDD` 8자리가 아니면 만료로 보지 않는다. 판정 불가를 종료로 취급하면
     * 아직 보호소에 있는 아이가 목록에서 사라진다. 모르면 남기는 쪽이 안전하다.
     *
     * ## 형식은 맞는데 값이 말이 안 되는 경우도 판정 불가다
     *
     * 상류에는 `happenDt = noticeSdt = noticeEdt` 인, 즉 **공고기간이 0일**인 레코드가 실제로 있다
     * (2026-07-27 실측: 표본 1,600건 중 73건 = 4.6%). 예: `413582202600529` 는 7/26 에 발견된
     * 고양이인데 공고종료일도 7/26 이다. 법정 기간이 7일 이상인데 0일일 수는 없으므로 이건
     * 상류 입력 오류다. 그대로 믿으면 **어제 구조된 아이가 오늘 목록에서 사라진다** —
     * 실제로 최근 10일 내 발견분 53건이 이렇게 숨겨졌다.
     *
     * 그래서 [noticeEdt] 를 그대로 믿지 않고 **[noticeSdt]와 [happenDt] 중 늦은 날 + 법정 최소기간**과
     * 비교해 늦은 쪽을 실효 종료일로 삼는다([effectiveEdt]).
     *
     * "짧으면 만료시키지 않는다" 로 처리하면 안 된다. 공고기간이 짧다는 건 **레코드 자체의 속성**이라
     * 날짜가 지나도 변하지 않으므로, 그 4.6% 가 **영원히 만료되지 않고 쌓인다** — 고치려던
     * "영원히 보호중으로 남는" 문제를 더 작은 부분집합에서 그대로 재현하는 셈이다.
     * 실효 종료일 방식은 그 아이도 결국(공고시작일·발견일 중 늦은 날 + 7일 뒤) 만료시키면서
     * 최소 노출 기간은 지킨다.
     *
     * [noticeSdt] 와 [happenDt] 모두 없거나 형식 불량이면 교차 검증을 못 하므로 [noticeEdt] 만으로 판정한다.
     */
    fun isOver(
        noticeEdt: String?,
        today: String = today(),
        noticeSdt: String? = null,
        happenDt: String? = null,
    ): Boolean {
        val edt = effectiveEdt(noticeSdt, noticeEdt, happenDt) ?: return false
        return edt < today
    }

    /**
     * 실효 공고종료일 = `max(noticeEdt, max(noticeSdt, happenDt) + MIN_NOTICE_DAYS)`.
     *
     * 정상 레코드(기간 10일)는 `noticeEdt` 가 그대로 이기므로 동작이 바뀌지 않는다.
     * 기간 0일짜리와 발견일이 늦은 공고는 기준일 + 7일로 밀려 최소 노출 기간을 확보한다.
     * 판정 불가(값 없음/형식 불량)면 `null` — 호출자는 만료로 보지 않는다.
     */
    fun effectiveEdt(
        noticeSdt: String?,
        noticeEdt: String?,
        happenDt: String? = null,
    ): String? {
        val end = parse(noticeEdt) ?: return null
        val anchor = listOfNotNull(parse(noticeSdt), parse(happenDt)).maxOrNull() ?: return noticeEdt
        val floor = runCatching { anchor.plusDays(MIN_NOTICE_DAYS) }.getOrNull() ?: return null
        if (floor.year !in 0..9999) return null
        return maxOf(end, floor).format(DateTimeFormatter.BASIC_ISO_DATE)
    }

    private fun parse(raw: String?): LocalDate? {
        if (raw == null || !YYYYMMDD.matches(raw)) return null
        return runCatching { LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
    }
}
