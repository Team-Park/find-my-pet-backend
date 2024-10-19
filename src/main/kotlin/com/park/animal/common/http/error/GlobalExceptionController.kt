package com.park.animal.common.http.error

import com.park.animal.common.http.error.exception.AuthException
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.common.http.response.FailedApiResponseBody
import com.park.animal.common.log.logger
import jakarta.servlet.http.HttpServletRequest
import org.hibernate.query.sqm.tree.SqmNode.log
import org.springframework.boot.logging.LogLevel
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionController {
    private val log = logger()

    @ExceptionHandler(BusinessException::class)
    fun businessException(e: BusinessException, request: HttpServletRequest): ResponseEntity<FailedApiResponseBody> {
        outputLog(errorCode = e.errorCode, e = e, path = request.requestURI)
        return e.toFailedBody()
    }

    @ExceptionHandler(AuthException::class)
    fun authExceptions(e: AuthException, request: HttpServletRequest): ResponseEntity<FailedApiResponseBody> {
        outputLog(errorCode = e.errorCode, e = e, path = request.requestURI)
        return e.toFailedBody()
    }

    @ExceptionHandler(Exception::class)
    fun unknownException(
        e: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<FailedApiResponseBody> {
        outputLog(errorCode = ErrorCode.UNKNOWN_ERROR, e = e, path = request.requestURI)
        return e.toFailedBody()
    }

    private fun outputLog(errorCode: ErrorCode, e: Exception, path: String) {
        when (errorCode.level) {
            LogLevel.ERROR -> {
                log.error(
                    """
            errorCode = ${errorCode.name}
            message = ${errorCode.message}
            requestPath = $path
            stackTrace = ${e.cause?.printStackTrace()}
            cause = ${e.cause}
            message = ${e.message}
                    """.trimIndent(),
                )
            }

            LogLevel.WARN -> {
                log.warn(
                    """
            errorCode = ${errorCode.name}
            message = ${errorCode.message}
            requestPath = $path
            stackTrace = ${e.cause?.printStackTrace()}
            cause = ${e.cause}
            message = ${e.message}
                    """.trimIndent(),
                )
            }

            else -> {}
        }
    }
}

fun BusinessException.toFailedBody(): ResponseEntity<FailedApiResponseBody> {
    val failedApiResponseBody = this.errorCode.toFailedResponseBody()
    return ResponseEntity.status(this.errorCode.httpCode.value()).body(failedApiResponseBody)
}

fun AuthException.toFailedBody(): ResponseEntity<FailedApiResponseBody> {
    val failedApiResponseBody = this.errorCode.toFailedResponseBody()
    return ResponseEntity.status(this.errorCode.httpCode.value()).body(failedApiResponseBody)
}

fun Exception.toFailedBody(): ResponseEntity<FailedApiResponseBody> {
    val failedApiResponseBody = ErrorCode.UNKNOWN_ERROR.toFailedResponseBody()
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(failedApiResponseBody)
}
