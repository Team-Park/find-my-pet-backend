package com.park.animal.common.resolver

import annotation.AuthenticationUser
import com.park.animal.common.constants.AuthConstants
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import constant.AuthConstant
import dto.UserContext
import jakarta.servlet.http.HttpServletRequest
import model.Role
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.util.UUID

@Component
class AuthenticationResolver : HandlerMethodArgumentResolver {
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
        val userIdAttribute = request.getAttribute(AuthConstants.USER_ID)
        return if (shouldAuthenticate(parameter) || userIdAttribute != null) {
            val userId =
                UUID.fromString(userIdAttribute.toString())
                    ?: throw BusinessException(ErrorCode.AUTHENTICATION_RESOLVER_ERROR)
            val role: Role =
                Role.valueOf(request.getAttribute(AuthConstants.USER_ROLE).toString()) as? Role
                    ?: throw BusinessException(ErrorCode.AUTHENTICATION_RESOLVER_ERROR)
            val userName: String = request.getAttribute(AuthConstant.USER_NAME).toString()
            val email = request.getAttribute(AuthConstant.USER_EMAIL).toString()
            UserContext(
                userId = userId,
                role = role,
                userName = userName,
                email = email,
            )
        } else {
            null
        }
    }

    private fun shouldAuthenticate(parameter: MethodParameter): Boolean {
        val authenticationUser = parameter.getParameterAnnotation(AuthenticationUser::class.java)
        return authenticationUser?.isRequired ?: false
    }
}
