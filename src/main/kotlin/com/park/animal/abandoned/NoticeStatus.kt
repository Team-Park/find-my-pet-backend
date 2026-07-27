package com.park.animal.abandoned

/**
 * 목록 조회의 공고 상태 필터.
 *
 * - [OPEN]   진행중 (`closed_at IS NULL`) — 기본값
 * - [CLOSED] 공고 종료 (`closed_at IS NOT NULL`)
 * - [ALL]    상태 무관
 *
 * 기본값이 [OPEN] 이어야 파라미터 없이 호출하는 기존 클라이언트(메인 목록 / 지역 SSR / sitemap)의
 * 계약이 그대로 유지된다.
 */
enum class NoticeStatus {
    OPEN,
    CLOSED,
    ALL,
    ;

    companion object {
        /**
         * 오타·대소문자·미지원 값은 [OPEN] 으로 폴백한다.
         * 잘못된 입력 때문에 이미 끝난 공고가 진행중인 것처럼 노출되는 쪽이 훨씬 나쁘다.
         */
        fun from(raw: String?): NoticeStatus =
            raw?.trim()?.uppercase()?.let { value -> entries.firstOrNull { it.name == value } } ?: OPEN
    }
}
