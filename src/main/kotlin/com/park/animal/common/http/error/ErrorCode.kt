package com.park.animal.common.http.error

enum class ErrorCode(
    val message: String,
) {
    NO_BEARER_TOKEN(""),
    EXPIRED_JWT(""),
    PARSE_JWT_FAILED(""),
    REISSUE_JWT_TOKEN_FAILURE(""),

    UNAUTHORIZED("작업을 수행할 권한이 없습니다."),

    AUTHENTICATION_RESOLVER_ERROR(""),
    NOT_FOUND_REQUEST(""),

    FAILURE_UPLOAD_IMAGE("이미지 업로드하는데 실패했습니다."),

    // post
    NOT_FOUND_POST("게시글 상세조회 실패"),
    NOT_FOUND_POST_IMAGE("게시글 이미지 조회 실패"),
}
