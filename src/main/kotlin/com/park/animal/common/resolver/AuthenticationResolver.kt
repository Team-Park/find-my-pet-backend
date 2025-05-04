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
import org.woo.mapper.Jackson

@Component
class AuthenticationResolver(
    private val authGrpcService: AuthGrpcService,
) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean = true

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any? {
        val request =
            webRequest.getNativeRequest(HttpServletRequest::class.java)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_REQUEST)
        val shouldAuthenticate = shouldAuthenticate(parameter)
        return runCatching {
            val passport = request.getPassport()
            if (hasAuthenticationUserAnnotation(parameter) && passport != null) {
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
            }
            return passport
        }.onFailure {
            if (shouldAuthenticate) {
                throw BusinessException(ErrorCode.FORBIDDEN)
            }
        }.getOrNull()
    }

    private fun shouldAuthenticate(parameter: MethodParameter): Boolean =
        parameter.getParameterAnnotation(AuthenticationUser::class.java)?.isRequired ?: false

    private fun hasAuthenticationUserAnnotation(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(AuthenticationUser::class.java)
}

fun HttpServletRequest.getPassport(): Passport? {
    val passportString = this.getHeader("X-User-Passport")
    if (passportString.isNullOrBlank()) {
        return null
    }
    return Jackson.readValue(passportString, Passport::class.java)
}
