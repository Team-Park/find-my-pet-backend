package com.park.animal.matching

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

/**
 * 플랫폼 spring-ai `POST /api/ai/vision` 래퍼.
 *
 * 현재는 Google Gemini Vision 만 지원. 다른 vendor 는 spring-ai 측 확장 대기.
 */
@Component
class VisionApiClient(
    @Qualifier("springAiWebClient") private val webClient: WebClient,
) {
    companion object {
        const val DEFAULT_VENDOR = "GOOGLE"
        const val DEFAULT_MODEL = "gemini-3-flash-preview"
    }

    data class ImagePayload(
        val source: String, // "url" / "base64" / "storage_key"
        val url: String? = null,
        val data: String? = null,
        val mimeType: String? = null,
        val bucket: String? = null,
        val key: String? = null,
    )

    data class Message(
        val role: String,
        val content: String,
    )

    data class ModelSpec(
        val vendor: String = DEFAULT_VENDOR,
        val version: String = DEFAULT_MODEL,
        val vendorOptions: Map<String, Any> = emptyMap(),
    )

    data class VisionRequest(
        val applicationId: String,
        val models: List<ModelSpec>,
        val messages: List<Message>,
        val images: List<ImagePayload>,
        val sessionId: String,
        val maxTokens: Int = 4000,
        val timeoutSeconds: Int = 120,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VisionResult(
        val vendor: String?,
        val result: String?,
        val isError: Boolean = false,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VisionEnvelope(
        val code: Int?,
        val data: List<VisionResult>?,
    )

    /**
     * 이미지 URL 들과 함께 프롬프트를 보내고 LLM 응답 텍스트를 받음.
     * @return 첫 번째 vendor 결과의 `result` 텍스트. 실패 시 null.
     */
    suspend fun vision(
        applicationId: String,
        sessionId: String,
        prompt: String,
        imageUrls: List<String>,
        maxTokens: Int = 4000,
    ): String? {
        val request =
            VisionRequest(
                applicationId = applicationId,
                models = listOf(ModelSpec()),
                messages = listOf(Message(role = "user", content = prompt)),
                images = imageUrls.map { ImagePayload(source = "url", url = it) },
                sessionId = sessionId,
                maxTokens = maxTokens,
            )

        val envelope =
            webClient
                .post()
                .uri("/api/ai/vision")
                .bodyValue(request)
                .retrieve()
                .bodyToMono<VisionEnvelope>()
                .awaitSingle()

        val first = envelope.data?.firstOrNull() ?: return null
        if (first.isError) return null
        return first.result
    }
}
