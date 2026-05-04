package com.park.animal.publicdata

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import java.io.File

@Configuration
class PublicDataConfig {
    companion object {
        const val BASE_URL = "https://apis.data.go.kr/1543061/abandonmentPublicService_v2"
        private val log = LoggerFactory.getLogger(PublicDataConfig::class.java)
    }

    /**
     * 공공데이터포털 인증키 주입.
     * - 운영(swarm): `PUBLIC_DATA_API_KEY_FILE=/run/secrets/public_data_api_key` → 파일 내용
     * - 로컬: `PUBLIC_DATA_API_KEY=xxx` env 직접 주입
     */
    @Bean(name = ["publicDataApiKey"])
    fun publicDataApiKey(
        @Value("\${PUBLIC_DATA_API_KEY:}") direct: String,
        @Value("\${PUBLIC_DATA_API_KEY_FILE:}") keyFile: String,
    ): String {
        val resolved =
            when {
                direct.isNotBlank() -> direct
                keyFile.isNotBlank() && File(keyFile).exists() -> File(keyFile).readText().trim()
                else -> ""
            }
        if (resolved.isBlank()) {
            log.warn("PUBLIC_DATA_API_KEY not configured — /abandoned-animals will fail (direct=blank, keyFile='{}')", keyFile)
        }
        return resolved
    }

    @Bean(name = ["publicDataWebClient"])
    fun publicDataWebClient(): WebClient =
        WebClient
            .builder()
            .baseUrl(BASE_URL)
            .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
            .build()
}
