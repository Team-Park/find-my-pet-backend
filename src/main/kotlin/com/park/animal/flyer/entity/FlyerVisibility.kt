package com.park.animal.flyer.entity

/**
 * 전단지 공개 범위.
 *
 * - [PRIVATE]: 게시글 작성자만 조회 가능 (회수 동선 관리 용도)
 * - [PUBLIC]: 게시글 상세 페이지에서 누구나 전단지 위치 확인 가능 — 이웃 제보 유도
 */
enum class FlyerVisibility {
    PRIVATE,
    PUBLIC,
}
