package com.park.animal.matching.dto

import java.time.LocalDateTime

/**
 * 실종 게시글에 대한 "닮은 아이" 후보 1건.
 *
 * 정확도보다 **사용자가 직접 보고 판단할 수 있도록 필요한 정보를 제공**하는 것이 목적.
 * 유사도 퍼센트는 내부 정렬용이며 UI에 노출하지 않는다 (§UX 지침 `prd/find-my-pet/search-radius-and-flyer.md`).
 */
data class MatchCandidate(
    /** 공공데이터 구조동물 desertionNo */
    val desertionNo: String,
    /** 썸네일 URL (popfile) */
    val photoUrl: String?,
    /** "[개] 말티즈" 원본 */
    val kindCd: String?,
    val sexCd: String?,
    val age: String?,
    val weight: String?,
    val specialMark: String?,
    val happenPlace: String?,
    val happenDt: String?,
    val careNm: String?,
    val careTel: String?,
    val careAddr: String?,
    /** 0.0 ~ 1.0. 내부 정렬용 — 프론트는 이 값을 직접 표시하지 않음 */
    val similarity: Double,
    /** Vision LLM 설명 (있으면 보호자에게 "왜 닮아 보였는지" 톤으로 제공) */
    val reasoning: String?,
    /** 알림 생성 시각 */
    val generatedAt: LocalDateTime,
)
