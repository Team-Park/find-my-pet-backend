package com.park.animal.common.http.error

import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LogLevel.ERROR
import org.springframework.boot.logging.LogLevel.WARN
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.woo.grpc.ErrorConverter
import org.woo.http.FailedApiResponseBody

enum class ErrorCode(
    val message: String,
    val httpCode: HttpStatusCode,
    val level: LogLevel,
) {
    NO_BEARER_TOKEN("", HttpStatus.UNAUTHORIZED, WARN),
    EXPIRED_JWT("", HttpStatus.UNAUTHORIZED, WARN),
    PARSE_JWT_FAILED("", HttpStatus.BAD_REQUEST, WARN),
    REISSUE_JWT_TOKEN_FAILURE("", HttpStatus.UNAUTHORIZED, WARN),

    FORBIDDEN("작업을 수행할 권한이 없습니다.", HttpStatus.FORBIDDEN, WARN),

    AUTHENTICATION_RESOLVER_ERROR("", HttpStatus.INTERNAL_SERVER_ERROR, ERROR),
    NOT_FOUND_REQUEST("", HttpStatus.BAD_REQUEST, WARN),

    // user
    NOT_FOUND_USER("user 를 찾을 수 없습니다", HttpStatus.BAD_REQUEST, WARN),

    // post
    NOT_FOUND_POST("게시글 상세조회 실패", HttpStatus.BAD_REQUEST, WARN),
    FAILURE_UPLOAD_IMAGE("이미지 업로드하는데 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, ERROR),
    NOT_FOUND_POST_IMAGE("게시글 이미지 조회 실패", HttpStatus.BAD_REQUEST, WARN),
    NOT_ALLOWED_FILE_TYPE("허용되지 않는 파일 형식입니다. 이미지(.jpg/.jpeg/.png/.gif)만 업로드할 수 있습니다.", HttpStatus.BAD_REQUEST, WARN),

    // review
    NOT_FOUND_REVIEW("리뷰 상세조회 실패", HttpStatus.BAD_REQUEST, WARN),

    // breed
    NOT_FOUND_BREED("품종을 찾을 수 없습니다", HttpStatus.BAD_REQUEST, WARN),
    MISMATCHED_BREED("선택한 동물 종과 품종이 일치하지 않습니다", HttpStatus.BAD_REQUEST, WARN),

    // flyer
    NOT_FOUND_FLYER("전단지를 찾을 수 없습니다", HttpStatus.BAD_REQUEST, WARN),

    // sighting
    NOT_FOUND_SIGHTING("목격 제보를 찾을 수 없습니다", HttpStatus.BAD_REQUEST, WARN),

    // notification
    NOT_FOUND_NOTIFICATION("알림을 찾을 수 없습니다", HttpStatus.BAD_REQUEST, WARN),

    // bookmark
    NOT_FOUND_BOOKMARK("즐겨찾기 항목을 찾을 수 없습니다", HttpStatus.BAD_REQUEST, WARN),
    DUPLICATE_BOOKMARK("이미 즐겨찾기에 추가된 게시글입니다", HttpStatus.BAD_REQUEST, WARN),

    // http 공통 — 라우트/파라미터 오류를 500 으로 흘리지 않기 위한 명시 매핑
    NOT_FOUND_ROUTE("요청한 경로를 찾을 수 없습니다", HttpStatus.NOT_FOUND, WARN),
    MISSING_PARAMETER("필수 파라미터가 누락되었거나 형식이 잘못되었습니다", HttpStatus.BAD_REQUEST, WARN),

    // 함께 찾기 (수색그룹/팀) — 설계 15.
    // 주의: 위쪽 legacy NOT_FOUND_* 는 전부 400 이지만, 이 블록은 설계 15 표를 지키기 위해
    // 의도적으로 404/409/410 을 쓴다. 기존 코드의 400 을 소급 변경하지 않는다(프론트 계약 파손).
    // 문구는 설계 2 제품 언어만 사용한다 — admin/좌표/전화번호/차단 사유 금지.
    //
    // 400 두 종류의 경계:
    //   MISSING_PARAMETER          = 요청이 컨트롤러 시그니처에 바인딩되지 못함(파라미터/헤더 누락,
    //                                UUID·enum 변환 실패, 깨진 JSON, Kotlin non-null 필드 누락).
    //                                GlobalExceptionController 가 자동 생성하며 서비스가 직접 던지지 않는다.
    //   INVALID_COLLABORATION_INPUT = 바인딩은 성공했으나 서비스 계층 명시 검증에 걸림
    //                                (팀 이름 길이 2~30자 위반, 설명·사유 길이 초과 등).
    //                                이 레포에는 spring-boot-starter-validation 이 없어 @Valid 가
    //                                무동작이므로 서비스가 직접 검사하고 이 코드를 던진다.
    NOT_FOUND_SEARCH_GROUP("요청한 정보를 찾을 수 없어요.", HttpStatus.NOT_FOUND, WARN),
    NOT_FOUND_SEARCH_GROUP_MEMBERSHIP("요청한 정보를 찾을 수 없어요.", HttpStatus.NOT_FOUND, WARN),
    NOT_FOUND_TEAM("요청한 정보를 찾을 수 없어요.", HttpStatus.NOT_FOUND, WARN),
    NOT_FOUND_TEAM_MEMBERSHIP("요청한 정보를 찾을 수 없어요.", HttpStatus.NOT_FOUND, WARN),
    NOT_FOUND_TEAM_SUPPORT("요청한 정보를 찾을 수 없어요.", HttpStatus.NOT_FOUND, WARN),
    SEARCH_GROUP_ACCESS_DENIED("현재 이 수색그룹을 이용할 권한이 없어요.", HttpStatus.FORBIDDEN, WARN),
    SEARCH_GROUP_STATE_CONFLICT("처리할 수 없는 상태예요. 최신 상태를 다시 불러와 주세요.", HttpStatus.CONFLICT, WARN),
    SEARCH_ALREADY_ENDED("종료된 수색이에요. 이전 기록만 확인할 수 있어요.", HttpStatus.GONE, WARN),
    TEAM_LEADER_REQUIRED("팀장만 할 수 있는 작업이에요.", HttpStatus.FORBIDDEN, WARN),
    TEAM_LEADER_CANNOT_LEAVE("팀장 권한을 다른 팀원에게 넘긴 뒤에 나갈 수 있어요.", HttpStatus.CONFLICT, WARN),
    INVALID_COLLABORATION_INPUT("입력값을 다시 확인해 주세요.", HttpStatus.BAD_REQUEST, WARN),

    UNKNOWN_ERROR("알 수 없는 에러", HttpStatus.INTERNAL_SERVER_ERROR, ERROR),
}

fun ErrorCode.toFailedResponseBody(): FailedApiResponseBody =
    FailedApiResponseBody(
        code = this.name,
        message = this.message,
    )

fun ErrorCode.toGrpcError() = ErrorConverter.toGrpcErrorResponse(message = this.message, status = this.httpCode.value())
