package com.park.animal.common.http.error

import com.park.animal.common.http.error.exception.BusinessException
import exception.AuthException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.logging.LogLevel
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.woo.apm.log.log
import org.woo.http.FailedApiResponseBody
import org.woo.storagesdk.exception.NotAllowedMimeTypeException
import exception.ErrorCode as AuthErrorCode
import exception.LogLevel as AuthLogLevel

@RestControllerAdvice
class GlobalExceptionController {
    @ExceptionHandler(BusinessException::class)
    fun businessException(
        e: BusinessException,
        request: HttpServletRequest,
    ): ResponseEntity<FailedApiResponseBody> {
        outputLog(errorCode = e.errorCode, e = e, path = request.requestURI)
        return e.toFailedBody()
    }

    @ExceptionHandler(AuthException::class)
    fun authExceptions(
        e: AuthException,
        request: HttpServletRequest,
    ): ResponseEntity<FailedApiResponseBody> {
        outputLog(errorCode = e.errorCode, e = e, path = request.requestURI)
        return e.toFailedBody()
    }

    @ExceptionHandler(NotAllowedMimeTypeException::class)
    fun notAllowedFileType(
        e: NotAllowedMimeTypeException,
        request: HttpServletRequest,
    ): ResponseEntity<FailedApiResponseBody> = respond(ErrorCode.NOT_ALLOWED_FILE_TYPE, e, request)

    @ExceptionHandler(NoResourceFoundException::class)
    fun noResourceFound(
        e: NoResourceFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<FailedApiResponseBody> = respond(ErrorCode.NOT_FOUND_ROUTE, e, request)

    @ExceptionHandler(MissingServletRequestParameterException::class, MethodArgumentTypeMismatchException::class)
    fun missingParameter(
        e: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<FailedApiResponseBody> = respond(ErrorCode.MISSING_PARAMETER, e, request)

    private fun respond(
        code: ErrorCode,
        e: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<FailedApiResponseBody> {
        outputLog(errorCode = code, e = e, path = request.requestURI)
        return ResponseEntity.status(code.httpCode).body(code.toFailedResponseBody())
    }

    @ExceptionHandler(Exception::class)
    fun unknownException(
        e: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<FailedApiResponseBody> {
        outputLog(errorCode = ErrorCode.UNKNOWN_ERROR, e = e, path = request.requestURI)
        return e.toFailedBody()
    }

    private fun outputLog(
        errorCode: AuthErrorCode,
        e: AuthException,
        path: String,
    ) {
        when (errorCode.level) {
            AuthLogLevel.ERROR -> {
                log().error(
                    """
                    errorCode = ${errorCode.name}
                    message = ${errorCode.message}
                    requestPath = $path
                    stackTrace = ${e.printStackTrace()}
                    cause = ${e.cause}
                    message = ${e.message}
                    """.trimIndent(),
                )
            }

            AuthLogLevel.WARN -> {
                log().warn(
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

    private fun outputLog(
        errorCode: ErrorCode,
        e: Exception,
        path: String,
    ) {
        when (errorCode.level) {
            LogLevel.ERROR -> {
                log().error(
                    """
                    errorCode = ${errorCode.name}
                    message = ${errorCode.message}
                    requestPath = $path
                    stackTrace = ${e.printStackTrace()}
                    cause = ${e.cause}
                    message = ${e.message}
                    """.trimIndent(),
                )
            }

            LogLevel.WARN -> {
                log().warn(
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
    val failedApiResponseBody =
        FailedApiResponseBody(
            code = errorCode.name,
            message = errorCode.message,
        )
    return ResponseEntity.status(this.errorCode.httpCode).body(failedApiResponseBody)
}

fun Exception.toFailedBody(): ResponseEntity<FailedApiResponseBody> {
    val failedApiResponseBody = ErrorCode.UNKNOWN_ERROR.toFailedResponseBody()
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(failedApiResponseBody)
}
