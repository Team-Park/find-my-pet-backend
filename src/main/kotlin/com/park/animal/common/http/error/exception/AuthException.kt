package com.park.animal.common.http.error.exception

import com.park.animal.common.http.error.ErrorCode

open class AuthException(open val errorCode: ErrorCode, override val cause: Throwable?) : RuntimeException()

data class ExpiredJwtException(override val errorCode: ErrorCode, override val cause: Throwable) : AuthException(errorCode = errorCode, cause = cause)

data class ParseJwtFailedException(override val errorCode: ErrorCode, override val cause: Throwable) : AuthException(errorCode = errorCode, cause = cause)

data class NoBearerTokenException(override val errorCode: ErrorCode, override val cause: Throwable?) : AuthException(errorCode = errorCode, cause = cause)
