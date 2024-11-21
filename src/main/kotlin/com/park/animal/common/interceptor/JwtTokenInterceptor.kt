package com.park.animal.common.interceptor

import com.park.animal.auth.service.JwtTokenService
import com.park.animal.common.annotation.PublicEndPoint
import com.park.animal.common.constants.AuthConstants
import com.park.animal.common.constants.AuthConstants.AUTHORIZATION_HEADER
import com.park.animal.common.constants.AuthConstants.BEARER_PREFIX
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.NoBearerTokenException
import com.park.animal.common.log.logger
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

@Component
class JwtTokenInterceptor(
    private val jwtTokenService: JwtTokenService,
) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.method.equals(HttpMethod.OPTIONS.name())) {
            return true
        }
        val handlerMethod = handler as? HandlerMethod
        val (isPublicRequest, token) = initializeTokenIfNeeded(handlerMethod, request)
        if (isPublicRequest) {
            logger().info("isPublic")
            return true
        }
        val claim = jwtTokenService.parseAccessToken(token)
        request.setAttribute(AuthConstants.USER_ID, claim[AuthConstants.USER_ID])
        request.setAttribute(AuthConstants.USER_ROLE, claim[AuthConstants.USER_ROLE])
        return true
    }

    private fun initializeTokenIfNeeded(
        handlerMethod: HandlerMethod?,
        request: HttpServletRequest,
    ): Pair<Boolean, String> {
        return runCatching {
            val token = request.getBearerTokenFromHeader()
            Pair(false, token)
        }.getOrElse {
            val hasAnnotation = handlerMethod?.hasMethodAnnotation(PublicEndPoint::class.java) ?: false
            Pair(hasAnnotation, "")
        }
    }
}

fun HttpServletRequest.getBearerTokenFromHeader(): String {
    this.getHeader(AUTHORIZATION_HEADER)?.let { token ->
        when {
            token.startsWith(BEARER_PREFIX) -> return token.substring(
                BEARER_PREFIX.length,
            )

            else -> throw NoBearerTokenException(ErrorCode.NO_BEARER_TOKEN, null)
        }
    } ?: throw NoBearerTokenException(ErrorCode.NO_BEARER_TOKEN, null)
}
