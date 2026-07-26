package com.park.animal.share

import annotation.PublicEndPoint
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import io.micrometer.core.instrument.MeterRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody

/** 공유 채널. 프론트 `ShareButtons` 의 버튼 4종과 1:1 대응한다. */
enum class ShareChannel {
    /** 카카오 JS SDK feed 공유 */
    KAKAO,

    /** Web Share API 시스템 공유 시트 */
    NATIVE,

    /** 링크 복사 */
    LINK_COPY,

    /** 당근 동네생활 붙여넣기용 문구 복사 */
    DAANGN_TEXT,
}

/** 무엇을 공유했는지. 실종 상세와 유기 상세 두 곳에 공유 버튼이 붙어 있다. */
enum class ShareContentType {
    LOST,
    ABANDONED,
}

data class RecordShareEventRequest(
    @Schema(description = "공유 채널", example = "KAKAO", allowableValues = ["KAKAO", "NATIVE", "LINK_COPY", "DAANGN_TEXT"])
    val channel: String,
    @Schema(description = "공유 대상 종류", example = "LOST", allowableValues = ["LOST", "ABANDONED"])
    val contentType: String,
)

/**
 * 공유 사용빈도 집계.
 *
 * 저장소를 두지 않고 Prometheus 카운터만 올린다 — "카카오 공유가 이번 주 몇 번" 같은 추이만 보면 되고,
 * 게시글별 상세 집계는 요구되지 않았다. 나중에 게시글별 수치가 필요해지면 그때 테이블을 만든다.
 *
 * **라벨에 id 를 넣지 않는다.** channel 4종 × contentType 2종 = 최대 8개 시계열로 고정된다.
 * postId 를 라벨로 쓰면 시계열이 게시글 수만큼 늘어나 Prometheus 를 망가뜨리고, 어떤 글이 공유됐는지가
 * 메트릭에 남아 개인정보 관점에서도 좋지 않다.
 *
 * enum 을 요청 본문 타입으로 직접 받지 않고 문자열로 받아 여기서 검증한다. Jackson 이 enum 변환에
 * 실패하면 `HttpMessageNotReadableException` 이 나는데 이 브랜치의 `GlobalExceptionController` 는
 * 그것을 매핑하지 않아 500 이 된다. 직접 검증하면 오타에 400 을 돌려줄 수 있고, 동시에 라벨
 * 카디널리티도 enum 값으로 제한된다.
 *
 * 인증을 요구하지 않는다(`@PublicEndPoint`) — 공유는 비로그인 사용자도 하고, 로그인 여부로 집계를
 * 나눌 요구도 없다. 그 대가로 누구나 카운터를 부풀릴 수 있지만, 값이 틀어질 뿐 권한이나 데이터가
 * 걸린 문제는 아니다. 수치를 신뢰해야 할 만큼 중요해지면 그때 rate limit 을 건다.
 */
@RestController
@RequestMapping("/api/v1")
class ShareEventController(
    private val meterRegistry: MeterRegistry,
) {
    @PostMapping("/share-events")
    @PublicEndPoint
    @Operation(
        summary = "공유 사용빈도 집계",
        description =
            "공유 버튼을 눌렀을 때 프론트가 fire-and-forget 으로 호출한다. Prometheus 카운터 " +
                "`fmp_share_total{channel,content_type}` 만 올리고 아무것도 저장하지 않는다.",
    )
    fun record(
        @RequestBody request: RecordShareEventRequest,
    ): SucceededApiResponseBody<Void> {
        val channel = parse<ShareChannel>(request.channel)
        val contentType = parse<ShareContentType>(request.contentType)
        meterRegistry
            .counter(SHARE_METRIC, "channel", channel.name, "content_type", contentType.name)
            .increment()
        return SucceededApiResponseBody(data = null)
    }

    private inline fun <reified T : Enum<T>> parse(raw: String): T =
        enumValues<T>().firstOrNull { it.name == raw }
            ?: throw BusinessException(ErrorCode.MISSING_PARAMETER)

    companion object {
        const val SHARE_METRIC = "fmp.share"
    }
}
