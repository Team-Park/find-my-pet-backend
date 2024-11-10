package com.park.animal.common.filter

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.MDC
import java.util.UUID

class MDCInitializationFilter : Filter {
    override fun doFilter(request: ServletRequest?, response: ServletResponse?, chain: FilterChain?) {
        val httpRequest = request as HttpServletRequest
        initializeMdcContext(httpRequest)
        try {
            chain!!.doFilter(request, response)
        } finally {
            clearMdcContext()
        }
    }

    private fun initializeMdcContext(httpRequest: HttpServletRequest) {
        var traceId = httpRequest.getHeader("X-Request-ID")

        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString()
        }

        MDC.put("X-Request-ID", traceId)
        MDC.put("method", httpRequest.method)
        MDC.put("path", httpRequest.requestURI)
    }

    private fun clearMdcContext() {
        MDC.remove("X-Request-ID")
        MDC.remove("method")
        MDC.remove("path")
    }
}
