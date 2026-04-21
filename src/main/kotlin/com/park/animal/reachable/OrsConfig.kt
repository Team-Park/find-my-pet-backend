package com.park.animal.reachable

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.io.File
import java.time.Duration

@Configuration
class OrsConfig {
    companion object {
        const val BASE_URL = "https://api.openrouteservice.org"
    }

    /**
     * OpenRouteService API key.
     * - 운영: `ORS_API_KEY_FILE=/run/secrets/ors_api_key` (swarm secret)
     * - 로컬: `ORS_API_KEY=xxx` 직접 env
     */
    @Bean(name = ["orsApiKey"])
    fun orsApiKey(
        @Value("\${ORS_API_KEY:}") direct: String,
        @Value("\${ORS_API_KEY_FILE:}") keyFile: String,
    ): String =
        when {
            direct.isNotBlank() -> direct
            keyFile.isNotBlank() -> File(keyFile).readText().trim()
            else -> ""
        }

    @Bean(name = ["orsWebClient"])
    fun orsWebClient(): WebClient {
        val httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(15))
        return WebClient
            .builder()
            .baseUrl(BASE_URL)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
