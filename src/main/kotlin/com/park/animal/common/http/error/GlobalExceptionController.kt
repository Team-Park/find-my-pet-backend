package com.park.animal.common.http.error

import com.park.animal.common.http.error.exception.AuthException
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.common.http.response.FailedApiResponseBody
import com.park.animal.common.log.logger
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionController {
    private val log = logger()

    @ExceptionHandler(BusinessException::class)
    fun businessException(e: BusinessException, request: HttpServletRequest): ResponseEntity<FailedApiResponseBody> {
        log.error(
            """
            error Code = ${e.errorCode},
            error Message = ${e.errorCode.message}
            exception message = ${e.message}
            requestPath = ${request.requestURI}
            """.trimIndent(),
        )
        return e.toFailedBody()
    }

    @ExceptionHandler(AuthException::class)
    fun authExceptions(e: AuthException, request: HttpServletRequest): ResponseEntity<FailedApiResponseBody> {
        log.error(
            """
                errorCode = ${e.errorCode.name}
                errorMessage = ${e.errorCode.message}
                exception message = ${e.message}
                requestPath = ${request.requestURI}
                cause = ${e.cause}
                throwable message = ${e.cause?.message}
            """.trimIndent(),
        )

        return e.toFailedBody()
    }

    @ExceptionHandler(Exception::class)
    fun unKnownException(
        e: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<FailedApiResponseBody> {
        log.error(
            """
            errorCode = ${ErrorCode.UNKNOWN_ERROR}
            message = ${ErrorCode.UNKNOWN_ERROR.message}
            requestPath = ${request.requestURI}
            stackTrace = ${e.printStackTrace()}
            message = ${e.message}
            """.trimIndent(),
        )
        return e.toFailedBody()
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
