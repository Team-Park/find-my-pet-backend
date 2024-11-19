package com.park.animal.common.resolver

import com.park.animal.auth.entity.Role
import com.park.animal.common.annotation.AuthenticationUser
import com.park.animal.common.constants.AuthConstants
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.util.*

@Component
class AuthenticationResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean {
        return shouldAuthenticate(parameter)
    }

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        webRequest.getNativeRequest(HttpServletRequest::class.java)?.let {
            if (shouldAuthenticate(parameter)) {
                val userId = UUID.fromString(it.getAttribute(AuthConstants.USER_ID).toString())
                    ?: throw BusinessException(ErrorCode.AUTHENTICATION_RESOLVER_ERROR)
                val role: Role = Role.valueOf(it.getAttribute(AuthConstants.USER_ROLE).toString()) as? Role
                    ?: throw BusinessException(ErrorCode.AUTHENTICATION_RESOLVER_ERROR)
                return UserContext(
                    userId = userId,
                    role = role,
                )
            } else {
                return ""
            }
        }
        throw BusinessException(ErrorCode.NOT_FOUND_REQUEST)
    }

    private fun shouldAuthenticate(parameter: MethodParameter): Boolean {
        val authenticationUser = parameter.getParameterAnnotation(AuthenticationUser::class.java)
        return authenticationUser?.isRequired ?: false
    }
}

data class UserContext(
    val userId: UUID,
    val role: Role,
)
