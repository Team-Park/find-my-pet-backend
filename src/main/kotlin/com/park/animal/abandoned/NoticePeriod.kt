package com.park.animal.abandoned

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 공공데이터 공고기간(`notice_sdt` ~ `notice_edt`) 판정.
 *
 * 왜 `notice_edt` 로 판정하는가: 상류(data.go.kr)의 `process_state` 는 실제로 갱신되지 않아
 * 발견 후 100일이 지난 공고도 "보호중" 으로 내려온다. 반면 `notice_edt` 는 우리가 이미 갖고 있고
 * 상류 응답 품질과 무관하며, 법정 공고기간이 끝난 공고는 정의상 진행중이 아니다.
 *
 * `notice_edt` 는 `YYYYMMDD` 고정폭이라 문자열 비교가 곧 날짜 비교다.
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
     * 법정 최소 공고기간(일). 동물보호법상 공고 후 7일이 지나야 소유권이 넘어간다.
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
     * 그래서 [noticeSdt] 를 함께 받아 공고기간이 법정 최소치([MIN_NOTICE_DAYS])보다 짧으면
     * 만료로 보지 않는다. 형식 검사와 같은 이유이고 같은 방향이다 — **의심스러우면 남긴다.**
     * [noticeSdt] 가 없거나 형식 불량이면 교차 검증을 못 하므로 [noticeEdt] 만으로 판정한다.
     */
    fun isOver(
        noticeEdt: String?,
        today: String = today(),
        noticeSdt: String? = null,
    ): Boolean {
        if (noticeEdt == null || !YYYYMMDD.matches(noticeEdt)) return false
        if (noticeEdt >= today) return false
        return !isImplausiblyShort(noticeSdt, noticeEdt)
    }

    /** 공고기간이 법정 최소치보다 짧은가 = 상류 데이터를 믿을 수 없는가. */
    private fun isImplausiblyShort(
        noticeSdt: String?,
        noticeEdt: String,
    ): Boolean {
        if (noticeSdt == null || !YYYYMMDD.matches(noticeSdt)) return false
        val start = runCatching { LocalDate.parse(noticeSdt, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() ?: return false
        val end = runCatching { LocalDate.parse(noticeEdt, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() ?: return false
        return ChronoUnit.DAYS.between(start, end) < MIN_NOTICE_DAYS
    }
}
