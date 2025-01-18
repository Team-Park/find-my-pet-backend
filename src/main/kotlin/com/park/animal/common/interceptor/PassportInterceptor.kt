package com.park.animal.common.interceptor

import annotation.PublicEndPoint
import com.park.animal.auth.external.AuthGrpcService
import dto.UserContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
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

    private fun initializeTokenIfNeeded(
        request: HttpServletRequest,
        handlerMethod: HandlerMethod?,
    ): Boolean =
        runCatching {
//            val token = request.getBearerTokenFromHeader()
//            val userInfo = runBlocking { authGrpcService.getUserInfo(token) }
//
//            request.setAttribute(AuthConstants.USER_ID, userInfo.id)
//            request.setAttribute(AuthConstants.USER_ROLE, userInfo.role)
//            request.setAttribute(AuthConstant.USER_NAME, userInfo.name)
//            request.setAttribute(AuthConstant.USER_EMAIL, userInfo.email)
            val passport = request.getPassport()
            request.setAttribute("passport", passport)
            true
        }.getOrElse {
            val publicEndPoint = handlerMethod?.hasMethodAnnotation(PublicEndPoint::class.java) ?: false
            if (publicEndPoint) {
                true
            } else {
                throw it
            }
        }
}

fun HttpServletRequest.getPassport(): UserContext? {
    val passportString = this.getHeader("X-User-Passport")
    return Jackson.readValue(passportString, UserContext::class.java)
}

// fun HttpServletRequest.getBearerTokenFromHeader(): String {
//    this.getHeader(AUTHORIZATION_HEADER)?.let { token ->
//        when {
//            token.startsWith(BEARER_PREFIX) -> return token.substring(
//                BEARER_PREFIX.length,
//            )
//
//            else -> throw NoBearerTokenException(ErrorCode.NO_BEARER_TOKEN, null)
//        }
//    } ?: throw NoBearerTokenException(ErrorCode.NO_BEARER_TOKEN, null)
// }
