package com.park.animal.common.http.error

import com.park.animal.common.http.error.exception.BusinessException
import exception.AuthException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.logging.LogLevel
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingRequestHeaderException
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

    /**
     * 요청 바인딩 실패 4종을 한 곳에서 400 [ErrorCode.MISSING_PARAMETER] 로 내린다.
     *
     * - MissingServletRequestParameterException: 필수 query/form 파라미터 누락
     * - MethodArgumentTypeMismatchException: UUID/enum 등 타입 변환 실패.
     *   `joinPolicy=WHATEVER` 처럼 enum 에 없는 문자열이 오는 경우가 여기다.
     * - HttpMessageNotReadableException: 깨진 JSON, 또는 Kotlin non-null 필드 누락으로
     *   Jackson 이 인스턴스화에 실패한 경우. 핸들러가 없어 500 으로 떨어지고 있었다(F9).
     *   이 레포에는 spring-boot-starter-validation 이 없어 @Valid 가 무동작이므로,
     *   본문 형태 오류를 400 으로 만드는 유일한 지점이 여기다.
     * - MissingRequestHeaderException: 필수 헤더 누락. ServletRequestBindingException 하위라
     *   MissingServletRequestParameterException 핸들러에 걸리지 않아 역시 500 이었다(F10).
     *
     * 경계 규약: 여기서 처리하는 것은 "요청이 컨트롤러 시그니처에 **바인딩되지 못한**" 경우뿐이다.
     * 바인딩은 됐지만 값이 도메인 규칙을 어긴 경우(팀 이름 길이 등)는 서비스가
     * [ErrorCode.INVALID_COLLABORATION_INPUT] 을 던진다. 두 코드를 섞지 않는다 —
     * 프론트가 "형식이 틀림" 과 "값이 규칙 위반" 을 다르게 안내한다.
     */
    @ExceptionHandler(
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
        HttpMessageNotReadableException::class,
        MissingRequestHeaderException::class,
    )
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
