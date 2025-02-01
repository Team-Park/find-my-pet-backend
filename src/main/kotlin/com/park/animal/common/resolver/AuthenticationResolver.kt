package com.park.animal.common.resolver

import annotation.AuthenticationUser
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

@Component
class AuthenticationResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean = shouldAuthenticate(parameter)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any? {
        val request =
            webRequest.getNativeRequest(HttpServletRequest::class.java)
                ?: throw BusinessException(ErrorCode.NOT_FOUND_REQUEST)
        val passport = request.getAttribute("passport")
        return passport
    }

    private fun shouldAuthenticate(parameter: MethodParameter): Boolean {
        val authenticationUser = parameter.getParameterAnnotation(AuthenticationUser::class.java)
        return authenticationUser?.isRequired ?: false
    }
}
