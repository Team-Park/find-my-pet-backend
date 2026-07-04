package com.park.animal.common.http.error

import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.servlet.resource.NoResourceFoundException
import jakarta.servlet.http.HttpServletRequest
import kotlin.test.assertEquals

/**
 * 라이브에서 관측된 오매핑 회귀 방지 (2026-07-04):
 * - 존재하지 않는 경로(NoResourceFoundException) → 500 UNKNOWN_ERROR 로 응답하던 것을 404 로
 * - 필수 파라미터 누락(MissingServletRequestParameterException) → 500 → 400 으로
 */
class GlobalExceptionMappingTest {
    private val controller = GlobalExceptionController()
    private val request =
        mock<HttpServletRequest>().also {
            whenever(it.requestURI).thenReturn("/some/path")
        }

    @Test
    fun `존재하지 않는 경로는 404 NOT_FOUND_ROUTE`() {
        val e = NoResourceFoundException(HttpMethod.GET, "user/me")

        val res = controller.noResourceFound(e, request)

        assertEquals(HttpStatus.NOT_FOUND, res.statusCode)
        assertEquals(ErrorCode.NOT_FOUND_ROUTE.name, res.body!!.code)
    }

    @Test
    fun `필수 파라미터 누락은 400 MISSING_PARAMETER`() {
        val e = MissingServletRequestParameterException("q", "String")

        val res = controller.missingParameter(e, request)

        assertEquals(HttpStatus.BAD_REQUEST, res.statusCode)
        assertEquals(ErrorCode.MISSING_PARAMETER.name, res.body!!.code)
    }
}
