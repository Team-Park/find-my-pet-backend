package com.park.animal.common.interceptor

import annotation.AuthenticationUser
import annotation.PublicEndPoint
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import dto.Passport
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.woo.apm.log.log
import org.woo.mapper.Jackson

/**
 * F-AUTH-3 (B안): default-deny 인증 게이트. forest `3e9df96` 패턴 mirror.
 *
 * 정책:
 *  - `@PublicEndPoint` 어노테이션 → 인증 없이 통과.
 *  - `@AuthenticationUser(isRequired = false)` → 토큰 없으면 통과, 있으면 검증.
 *  - `@AuthenticationUser` (기본 isRequired=true) → 토큰 필수.
 *  - 둘 다 없는 endpoint → **default-deny: 403**. (어노테이션 누락 시 무인증 노출 방지)
 *
 * 보안 전제: 게이트웨이가 `X-User-Passport` 헤더를 발행하고 클라이언트 위조분은 strip 한다
 * (`prd/platform/api-spec.md` 6.2 참조).
 */
@Component
class PassportInterceptor : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (request.method.equals(HttpMethod.OPTIONS.name())) return true
        val handlerMethod = handler as? HandlerMethod ?: return true

        // @PublicEndPoint → 통과 (method or class level)
        if (handlerMethod.hasMethodAnnotation(PublicEndPoint::class.java)) return true
        if (handlerMethod.beanType.isAnnotationPresent(PublicEndPoint::class.java)) return true

        val authParam = findAuthenticationUserParam(handlerMethod)
        val passportPresent = request.getHeader(PASSPORT_HEADER)?.isNotBlank() == true

        return when {
            // 어노테이션 없음 → default-deny
            authParam == null -> {
                log().warn("PassportInterceptor: ${handlerMethod.method} has no @PublicEndPoint nor @AuthenticationUser — denying")
                throw BusinessException(ErrorCode.FORBIDDEN)
            }
            // isRequired=true 인데 헤더 없으면 차단
            authParam.isRequired && !passportPresent ->
                throw BusinessException(ErrorCode.FORBIDDEN)
            // 헤더 있으면 파싱 실패 시 차단
            passportPresent && !canParsePassport(request) ->
                throw BusinessException(ErrorCode.FORBIDDEN)
            else -> true
        }
    }

    private fun findAuthenticationUserParam(handlerMethod: HandlerMethod): AuthenticationUser? =
        handlerMethod.methodParameters
            .firstOrNull { it.parameterType == Passport::class.java }
            ?.getParameterAnnotation(AuthenticationUser::class.java)

    private fun canParsePassport(request: HttpServletRequest): Boolean {
        val raw = request.getHeader(PASSPORT_HEADER) ?: return false
        return try {
            Jackson.readValue(raw, Passport::class.java)
            true
        } catch (e: Exception) {
            log().warn("PassportInterceptor: invalid X-User-Passport — ${e.message}")
            false
        }
    }

    companion object {
        private const val PASSPORT_HEADER = "X-User-Passport"
    }
}
