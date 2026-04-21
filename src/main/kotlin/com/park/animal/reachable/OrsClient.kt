package com.park.animal.reachable

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

/**
 * OpenRouteService `POST /v2/isochrones/foot-walking` 호출.
 *
 * 응답은 GeoJSON FeatureCollection — `features[i].geometry.coordinates` 가 polygon.
 */
@Component
class OrsClient(
    @Qualifier("orsWebClient") private val webClient: WebClient,
    @Qualifier("orsApiKey") private val apiKey: String,
) {
    companion object {
        const val PATH = "/v2/isochrones/foot-walking"
    }

    suspend fun isochroneByDistance(
        lat: Double,
        lng: Double,
        rangesMeters: List<Int>,
    ): IsochroneResponse {
        if (apiKey.isBlank()) throw IllegalStateException("ORS_API_KEY not configured")
        val body =
            mapOf(
                "locations" to listOf(listOf(lng, lat)),
                "range" to rangesMeters,
                "range_type" to "distance",
                "attributes" to listOf("area"),
                "smoothing" to 25,
            )
        return webClient
            .post()
            .uri(PATH)
            .header("Authorization", apiKey)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/geo+json")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(IsochroneResponse::class.java)
            .awaitSingle()
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class IsochroneResponse(
        val type: String?,
        val features: List<Feature>?,
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Feature(
            val type: String?,
            val properties: Properties?,
            val geometry: Geometry?,
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Properties(
            val group_index: Int?,
            val value: Double?,
            val center: List<Double>?,
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Geometry(
            val type: String?,
            /** 3중 리스트: ring → point → [lng, lat] */
            val coordinates: List<List<List<Double>>>?,
        )
    }
}
