package com.park.animal.abandoned

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
     * 공고기간이 [today] 기준으로 이미 끝났는지.
     *
     * 값이 없거나 `YYYYMMDD` 8자리 숫자가 아니면 **만료로 보지 않는다** — 판정 불가를 종료로
     * 취급하면 아직 보호소에 있는 아이가 목록에서 사라진다. 모르면 남기는 쪽이 안전하다.
     */
    fun isOver(
        noticeEdt: String?,
        today: String = today(),
    ): Boolean {
        if (noticeEdt == null || !YYYYMMDD.matches(noticeEdt)) return false
        return noticeEdt < today
    }
}
