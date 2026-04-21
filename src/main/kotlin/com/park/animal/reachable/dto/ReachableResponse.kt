package com.park.animal.reachable.dto

/**
 * 실종 게시글의 도달 가능 영역 응답.
 *
 * `method` 로 프론트가 렌더 전략 분기:
 * - `ORS_ISOCHRONE` → GeoJSON polygon 3개 렌더
 * - `CIRCLE_FALLBACK` → 단순 원 3개 렌더
 */
sealed class ReachableResponse {
    abstract val method: String

    data class OrsIsochrone(
        override val method: String = "ORS_ISOCHRONE",
        val features: List<Feature>,
    ) : ReachableResponse() {
        data class Feature(
            /** CORE / LIKELY / POSSIBLE */
            val level: String,
            /** `[[[lng, lat], [lng, lat], ...]]` 외곽선 → 닫힌 polygon */
            val polygon: List<List<List<Double>>>,
        )
    }

    data class CircleFallback(
        override val method: String = "CIRCLE_FALLBACK",
        val center: Center,
        val bands: Bands,
    ) : ReachableResponse() {
        data class Center(
            val lat: Double,
            val lng: Double,
        )

        data class Bands(
            val core: Double,
            val likely: Double,
            val possible: Double,
        )
    }
}
