package com.park.animal.common.resolver

import annotation.AuthenticationUser
import com.park.animal.auth.external.AuthGrpcService
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import constant.AuthConstant
import dto.Passport
import dto.UserContext
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.woo.apm.log.log
import org.woo.mapper.Jackson

@Component
class AuthenticationResolver(
    private val authGrpcService: AuthGrpcService,
) : HandlerMethodArgumentResolver {
    // F-AUTH-1: 어노테이션이 붙은 Passport 파라미터만 처리. true 리턴은 모든 파라미터 가로챔.
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(AuthenticationUser::class.java) &&
            parameter.parameterType == Passport::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any? {
        val request =
            webRequest.getNativeRequest(HttpServletRequest::class.java)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_REQUEST)
        val isRequired = isAuthenticationRequired(parameter)

        // F-AUTH-2: passport 가 null 인데 isRequired=true 면 명시적 FORBIDDEN.
        // 기존 코드는 null 을 그대로 리턴해 Kotlin null-check 500 발생 (UserController.me NPE).
        val passport =
            try {
                request.getPassport()
            } catch (e: Exception) {
                log().warn("failed to parse X-User-Passport: ${e.message}")
                null
            }

        if (passport == null) {
            if (isRequired) throw BusinessException(ErrorCode.FORBIDDEN)
            return null
        }

        return runCatching {
            runBlocking {
                val token = request.getHeader(AuthConstant.AUTHORIZATION_HEADER)
                passport.ensureUserContextLoaded {
                    val userInfo = authGrpcService.getUserInfo(token)
                    UserContext(
                        email = userInfo.email,
                        userName = userInfo.name,
                        applicationRole = userInfo.applicationRole,
                        accessLevel = userInfo.accessLevel,
                    )
                }
            }
            passport
        }.onFailure {
            log().warn("failed to load user context cause ${it.cause} message = ${it.message}")
            if (isRequired) throw BusinessException(ErrorCode.FORBIDDEN)
        }.getOrElse { passport } // userContext 로드 실패 + isRequired=false → passport 자체는 반환
    }

    private fun isAuthenticationRequired(parameter: MethodParameter): Boolean =
        parameter.getParameterAnnotation(AuthenticationUser::class.java)?.isRequired ?: false
}

fun HttpServletRequest.getPassport(): Passport? {
    val passportString = this.getHeader("X-User-Passport")
    if (passportString.isNullOrBlank()) {
        return null
    }
    return Jackson.readValue(passportString, Passport::class.java)
}
