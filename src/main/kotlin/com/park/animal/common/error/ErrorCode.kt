package com.park.animal.common.error

enum class ErrorCode(
    val message: String,
) {
    NO_BEARER_TOKEN(""),
    EXPIRED_JWT(""),
    PARSE_JWT_FAILED(""),
    REISSUE_JWT_TOKEN_FAILURE(""),
}
