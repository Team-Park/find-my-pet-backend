package com.park.animal.common.http.error

import com.park.animal.common.http.response.FailedApiResponseBody
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LogLevel.ERROR
import org.springframework.boot.logging.LogLevel.WARN
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode

enum class ErrorCode(
    val message: String,
    val httpCode: HttpStatusCode,
    val level: LogLevel,
) {
    NO_BEARER_TOKEN("", HttpStatus.UNAUTHORIZED, WARN),
    EXPIRED_JWT("", HttpStatus.UNAUTHORIZED, WARN),
    PARSE_JWT_FAILED("", HttpStatus.BAD_REQUEST, WARN),
    REISSUE_JWT_TOKEN_FAILURE("", HttpStatus.UNAUTHORIZED, WARN),

    FORBIDDEN("작업을 수행할 권한이 없습니다.", HttpStatus.FORBIDDEN, WARN),

    AUTHENTICATION_RESOLVER_ERROR("", HttpStatus.INTERNAL_SERVER_ERROR, ERROR),
    NOT_FOUND_REQUEST("", HttpStatus.BAD_REQUEST, WARN),

    // user
    NOT_FOUND_USER("user 를 찾을 수 없습니다", HttpStatus.BAD_REQUEST, WARN),

    // post
    NOT_FOUND_POST("게시글 상세조회 실패", HttpStatus.BAD_REQUEST, WARN),
    FAILURE_UPLOAD_IMAGE("이미지 업로드하는데 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, ERROR),
    NOT_FOUND_POST_IMAGE("게시글 이미지 조회 실패", HttpStatus.BAD_REQUEST, WARN),

    UNKNOWN_ERROR("알 수 없는 에러", HttpStatus.INTERNAL_SERVER_ERROR, ERROR),
}

fun ErrorCode.toFailedResponseBody(): FailedApiResponseBody =
    FailedApiResponseBody(
        code = this.name,
        message = this.message,
    )
