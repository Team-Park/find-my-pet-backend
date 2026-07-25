package com.park.animal.common.http.error

import com.park.animal.common.http.error.exception.BusinessException
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.core.MethodParameter
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.servlet.resource.NoResourceFoundException
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 라이브에서 관측된 오매핑 회귀 방지 (2026-07-04):
 * - 존재하지 않는 경로(NoResourceFoundException) → 500 UNKNOWN_ERROR 로 응답하던 것을 404 로
 * - 필수 파라미터 누락(MissingServletRequestParameterException) → 500 → 400 으로
 *
 * 2026-07-25 확장 (함께 찾기 phase 1):
 * - 잘못된 JSON 본문 / Kotlin non-null 필드 누락(HttpMessageNotReadableException) → 500 → 400
 * - 필수 헤더 누락(MissingRequestHeaderException, ServletRequestBindingException 하위라
 *   기존 핸들러에 걸리지 않았다) → 500 → 400
 * - 함께 찾기 ErrorCode 는 설계 15 표(404/403/409/410/400)를 그대로 쓴다. 기존 legacy
 *   NOT_FOUND_* 가 전부 400 인 관례를 의도적으로 깨는 것이므로 상태값을 테스트로 고정한다.
 *
 * 사용 경계: 바인딩/역직렬화 실패는 여기서 MISSING_PARAMETER(400) 가 된다.
 * INVALID_COLLABORATION_INPUT(400) 은 서비스 계층 명시 검증 실패 전용이며 컨트롤러 진입 전에는
 * 절대 발생하지 않는다.
 */
class GlobalExceptionMappingTest {
    private val controller = GlobalExceptionController()
    private val request =
        mock<HttpServletRequest>().also {
            whenever(it.requestURI).thenReturn("/some/path")
        }

    /** MissingRequestHeaderException 생성에 필요한 MethodParameter 용 더미 시그니처. */
    @Suppress("unused")
    fun headerBindingStub(groupId: String): String = groupId

    private fun headerParameter(): MethodParameter =
        MethodParameter(
            GlobalExceptionMappingTest::class.java.getDeclaredMethod("headerBindingStub", String::class.java),
            0,
        )

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

    @Test
    fun `잘못된 JSON 본문은 400 MISSING_PARAMETER`() {
        val e =
            HttpMessageNotReadableException(
                "JSON parse error: Instantiation of [simple type, class RegisterMembershipRequest] value failed",
                mock<HttpInputMessage>(),
            )

        val res = controller.missingParameter(e, request)

        assertEquals(HttpStatus.BAD_REQUEST, res.statusCode)
        assertEquals(ErrorCode.MISSING_PARAMETER.name, res.body!!.code)
    }

    @Test
    fun `필수 헤더 누락은 400 MISSING_PARAMETER`() {
        val e = MissingRequestHeaderException("X-Passport", headerParameter())

        val res = controller.missingParameter(e, request)

        assertEquals(HttpStatus.BAD_REQUEST, res.statusCode)
        assertEquals(ErrorCode.MISSING_PARAMETER.name, res.body!!.code)
    }

    @Test
    fun `함께 찾기 ErrorCode 는 설계 15 표의 HTTP 상태를 쓴다`() {
        assertEquals(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND_SEARCH_GROUP.httpCode)
        assertEquals(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP.httpCode)
        assertEquals(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND_TEAM.httpCode)
        assertEquals(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP.httpCode)
        assertEquals(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND_TEAM_SUPPORT.httpCode)
        assertEquals(HttpStatus.FORBIDDEN, ErrorCode.SEARCH_GROUP_ACCESS_DENIED.httpCode)
        assertEquals(HttpStatus.CONFLICT, ErrorCode.SEARCH_GROUP_STATE_CONFLICT.httpCode)
        assertEquals(HttpStatus.GONE, ErrorCode.SEARCH_ALREADY_ENDED.httpCode)
        assertEquals(HttpStatus.FORBIDDEN, ErrorCode.TEAM_LEADER_REQUIRED.httpCode)
        assertEquals(HttpStatus.CONFLICT, ErrorCode.TEAM_LEADER_CANNOT_LEAVE.httpCode)
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_COLLABORATION_INPUT.httpCode)

        // 바인딩 실패용 400 과 서비스 검증 실패용 400 은 서로 다른 코드로 유지한다.
        // 프론트가 "형식이 틀림" 과 "값이 규칙 위반" 을 구분해 안내할 수 있어야 한다.
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_PARAMETER.httpCode)
        assertFalse(ErrorCode.MISSING_PARAMETER == ErrorCode.INVALID_COLLABORATION_INPUT)

        // legacy 400 관례는 소급 변경하지 않는다 (프론트 계약 파손 방지).
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCode.NOT_FOUND_POST.httpCode)
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCode.NOT_FOUND_NOTIFICATION.httpCode)
    }

    @Test
    fun `함께 찾기 ErrorCode 문구에 admin 어휘가 없다`() {
        val collaborationCodes =
            listOf(
                ErrorCode.NOT_FOUND_SEARCH_GROUP,
                ErrorCode.NOT_FOUND_SEARCH_GROUP_MEMBERSHIP,
                ErrorCode.NOT_FOUND_TEAM,
                ErrorCode.NOT_FOUND_TEAM_MEMBERSHIP,
                ErrorCode.NOT_FOUND_TEAM_SUPPORT,
                ErrorCode.SEARCH_GROUP_ACCESS_DENIED,
                ErrorCode.SEARCH_GROUP_STATE_CONFLICT,
                ErrorCode.SEARCH_ALREADY_ENDED,
                ErrorCode.TEAM_LEADER_REQUIRED,
                ErrorCode.TEAM_LEADER_CANNOT_LEAVE,
                ErrorCode.INVALID_COLLABORATION_INPUT,
            )

        collaborationCodes.forEach {
            assertFalse(
                it.message.lowercase().contains("admin"),
                "${it.name} 문구에 admin 이 들어갔다. 설계 2 제품 언어 위반: ${it.message}",
            )
            assertFalse(it.message.isBlank(), "${it.name} 문구가 비어 있다")
        }
    }

    @Test
    fun `종료된 수색은 410 SEARCH_ALREADY_ENDED 로 응답한다`() {
        val res = controller.businessException(BusinessException(ErrorCode.SEARCH_ALREADY_ENDED), request)

        assertEquals(HttpStatus.GONE, res.statusCode)
        assertEquals(ErrorCode.SEARCH_ALREADY_ENDED.name, res.body!!.code)
        assertEquals("종료된 수색이에요. 이전 기록만 확인할 수 있어요.", res.body!!.message)
    }
}
