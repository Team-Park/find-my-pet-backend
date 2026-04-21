package com.park.animal.matching

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * 플랫폼 spring-ai 서비스(`POST /api/ai/vision`) 호출용 WebClient.
 * 컨테이너 내부 docker 서비스명(`spring-ai:8080`)으로 직접 호출.
 */
@Configuration
class SpringAiConfig {
    @Bean(name = ["springAiWebClient"])
    fun springAiWebClient(
        @Value("\${spring-ai.base-url:http://spring-ai:8080}") baseUrl: String,
        @Value("\${spring-ai.timeout-seconds:180}") timeoutSec: Long,
    ): WebClient {
        val httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(timeoutSec))
        return WebClient
            .builder()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs {
                // Vision API 응답은 4000 토큰까지 — 기본 256KB 로도 충분하지만 여유
                it.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)
            }.build()
    }
}
