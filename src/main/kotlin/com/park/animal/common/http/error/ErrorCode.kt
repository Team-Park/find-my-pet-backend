package com.park.animal.common.http.error

enum class ErrorCode(
    val message: String,
) {
    NO_BEARER_TOKEN(""),
    EXPIRED_JWT(""),
    PARSE_JWT_FAILED(""),
    REISSUE_JWT_TOKEN_FAILURE(""),

    AUTHENTICATION_RESOLVER_ERROR(""),
    NOT_FOUND_REQUEST(""),


    FAILURE_UPLOAD_IMAGE("이미지 업로드하는데 실패했습니다."),
}
