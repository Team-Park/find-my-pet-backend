package com.park.animal.publicdata.dto

import java.time.LocalDateTime

data class AbandonedAnimalResponse(
    /** 공공데이터 원본 desertionNo (유실번호) */
    val desertionNo: String,
    val filename: String?,
    val popfile: String?,
    /** "[개] 말티즈" 형태 */
    val kindCd: String?,
    /** "F" / "M" / "Q" (알수없음) */
    val sexCd: String?,
    /** "2025(년생)" */
    val age: String?,
    /** "3.2(Kg)" */
    val weight: String?,
    val specialMark: String?,
    val happenPlace: String?,
    /** YYYYMMDD */
    val happenDt: String?,
    /** 발견 시 추정 위도 (원 API 미제공 — 서비스 가공 시 채워질 수도) */
    val careNm: String?,
    val careTel: String?,
    val careAddr: String?,
    /** 보호중 / 종료(반환) / 종료(입양) 등 */
    val processState: String?,
    val noticeNo: String?,
    val noticeSdt: String?,
    val noticeEdt: String?,
    /** DOG / CAT / OTHER — kindCd 기반 서버에서 분류 */
    val animalType: String?,
    /** 관할 행정명 ("경상남도 거창군"). 시도/시군구 코드 매핑에 사용. */
    val orgNm: String? = null,
    /**
     * 공고 종료 여부. `processState` 로는 판정할 수 없다 — 상류가 100일 지난 공고도 "보호중" 으로 준다.
     *
     * true 여도 상세는 200 으로 살아 있다(이미 색인된 URL 을 404 로 만들지 않는다).
     * 프론트는 이 값으로 "공고 기간이 종료되었습니다" 안내 배너 + noindex 를 판정한다.
     * 주의: "공고 종료" 는 "안락사" 가 아니다. `processState` 는 원본 그대로 유지된다.
     */
    val noticeClosed: Boolean = false,
    /** 공고 종료로 마킹된 시각. [noticeClosed] 가 false 면 null. 공고 만료일 자체는 `noticeEdt`. */
    val noticeClosedAt: LocalDateTime? = null,
)
