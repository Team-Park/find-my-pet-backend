package com.park.animal.common.http.error

import com.park.animal.common.http.error.exception.AuthException
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.common.http.response.FailedApiResponseBody
import com.park.animal.common.log.logger
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionController {
    private val log = logger()

    @ExceptionHandler(BusinessException::class)
    fun businessException(e: BusinessException, request: HttpServletRequest): FailedApiResponseBody {
        log.error(
            """
            error Code = ${e.errorCode},
            error Message = ${e.errorCode.message}
            exception message = ${e.message}
            requestPath = ${request.requestURI}
            """.trimIndent(),
        )
        return FailedApiResponseBody(code = e.errorCode.name, message = e.errorCode.message)
    }

    @ExceptionHandler(AuthException::class)
    fun authExceptions(e: AuthException, request: HttpServletRequest): FailedApiResponseBody {
        log.error(
            """
                errorCode = ${e.errorCode.name}
                errorMessage = ${e.errorCode.message}
                exception message = ${e.message}
                requestPath = ${request.requestURI}
            """.trimIndent(),
        )
        return FailedApiResponseBody(code = e.errorCode.name, message = e.errorCode.message)
    }

    @ExceptionHandler(Exception::class)
    fun unKnownException(
        e: Exception,
        request: HttpServletRequest,
    ): FailedApiResponseBody {
        log.error(
            """
            errorCode = ${ErrorCode.UNKNOWN_ERROR}
            message = ${ErrorCode.UNKNOWN_ERROR.message}
            requestPath = ${request.requestURI}
            stackTrace = ${e.printStackTrace()}
            """.trimIndent(),
        )
        return FailedApiResponseBody(code = ErrorCode.UNKNOWN_ERROR.name, message = ErrorCode.UNKNOWN_ERROR.message)
    }
}
