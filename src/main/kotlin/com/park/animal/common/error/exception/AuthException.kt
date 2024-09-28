package com.park.animal.common.error.exception

import com.park.animal.common.error.ErrorCode

open class AuthException(open val errorCode: ErrorCode) : RuntimeException()

data class ExpiredJwtException(override val errorCode: ErrorCode) : AuthException(errorCode = errorCode)

data class ParseJwtFailedException(override val errorCode: ErrorCode) : AuthException(errorCode = errorCode)

data class NoBearerTokenException(override val errorCode: ErrorCode) : AuthException(errorCode = errorCode)
