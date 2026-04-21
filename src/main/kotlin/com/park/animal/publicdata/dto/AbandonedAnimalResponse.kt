package com.park.animal.publicdata.dto

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
)
