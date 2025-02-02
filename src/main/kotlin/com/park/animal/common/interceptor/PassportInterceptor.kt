package com.park.animal.common.interceptor

import annotation.PublicEndPoint
import com.park.animal.auth.external.AuthGrpcService
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import dto.UserContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import org.woo.mapper.Jackson

@Component
class PassportInterceptor(
    val authGrpcService: AuthGrpcService,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (request.method.equals(HttpMethod.OPTIONS.name())) {
            return true
        }
        val handlerMethod = handler as? HandlerMethod
        return initializeTokenIfNeeded(request, handlerMethod)
    }

    override fun postHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        modelAndView: ModelAndView?,
    ) {
        super.postHandle(request, response, handler, modelAndView)
    }

    private fun initializeTokenIfNeeded(
        request: HttpServletRequest,
        handlerMethod: HandlerMethod?,
    ): Boolean =
        runCatching {
            val passport = request.getPassport()
            request.setAttribute("passport", passport)
            true
        }.getOrElse {
            val publicEndPoint = handlerMethod?.hasMethodAnnotation(PublicEndPoint::class.java) ?: false
            if (publicEndPoint) {
                true
            } else {
                throw BusinessException(ErrorCode.FORBIDDEN)
            }
        }
}

fun HttpServletRequest.getPassport(): UserContext? {
    val passportString = this.getHeader("X-User-Passport")
    return Jackson.readValue(passportString, UserContext::class.java)
}
