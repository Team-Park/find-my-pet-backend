package com.park.animal.reachable

import com.fasterxml.jackson.databind.ObjectMapper
import com.park.animal.breed.entity.AnimalType
import com.park.animal.breed.repository.BreedRepository
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import com.park.animal.reachable.dto.ReachableResponse
import com.park.animal.redis.RedisDriver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import kotlin.jvm.optionals.getOrNull
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * 실종 위치 + 품종 + 경과시간 기반 도달 영역 계산.
 *
 * - DOG → ORS isochrone (`foot-walking`, distance 기반). ORS 실패 시 원 fallback.
 * - CAT/OTHER → 공식 미사용, 고정 반경 원 반환.
 */
@Service
class ReachableService(
    private val postRepository: PostRepository,
    private val breedRepository: BreedRepository,
    private val orsClient: OrsClient,
    private val redisDriver: RedisDriver,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private const val CACHE_TTL_SECONDS = 7L * 24 * 3600 // 7일
        private const val CACHE_KEY_PREFIX = "fmp:reachable"

        /**
         * 시간(h) → likely(95% 발견 거리, m) 통계 lookup table.
         * 출처: PetFBI / Missing Pet Partnership 실종 반려동물 발견 거리 통계 기반.
         * 단순 누적 이동거리(speed×time) 가 아니라 **활동시간 비율 + 배회 패턴이 반영된 직선 변위 95% 분위수**.
         * - DOG: 50% < 0.8km / 75% < 1.6km / 95% < 5km (3일 기준)
         * - CAT: 60% < 100m, 7일 후부터 천천히 확장
         */
        private val DOG_LIKELY_TABLE: List<Pair<Double, Double>> =
            listOf(
                1.0 to 200.0,
                6.0 to 800.0,
                24.0 to 1500.0,
                72.0 to 3000.0,
                168.0 to 5000.0,
                336.0 to 8000.0,
                720.0 to 12000.0,
            )

        private val CAT_LIKELY_TABLE: List<Pair<Double, Double>> =
            listOf(
                1.0 to 50.0,
                24.0 to 100.0,
                72.0 to 200.0,
                168.0 to 300.0,
                336.0 to 500.0,
                720.0 to 800.0,
            )

        private const val DOG_CAP_M = 12_000.0
        private const val CAT_CAP_M = 1_500.0

        // 기타 고정 반경
        private const val OTHER_CORE = 30.0
        private const val OTHER_LIKELY = 100.0
        private const val OTHER_POSSIBLE = 300.0
    }

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun computeReachable(postId: UUID): ReachableResponse {
        val post = postRepository.findById(postId).getOrNull() ?: throw BusinessException(ErrorCode.NOT_FOUND_POST)
        return when (post.animalType) {
            AnimalType.CAT -> catCircle(post)
            AnimalType.OTHER -> otherCircle(post)
            AnimalType.DOG -> dogReachable(post)
        }
    }

    // ───── DOG: ORS 우선 → 실패 시 원 fallback ─────
    private suspend fun dogReachable(post: Post): ReachableResponse {
        val bands = computeDogBands(post)
        val cacheKey = buildCacheKey(post.lat, post.lng, post.breedId)

        redisDriver.getValue(cacheKey, String::class.java)?.let { cached ->
            return objectMapper.readValue(cached, ReachableResponse.OrsIsochrone::class.java)
        }

        return runCatching {
            val isoResp =
                orsClient.isochroneByDistance(
                    lat = post.lat,
                    lng = post.lng,
                    rangesMeters = listOf(bands.core.toInt(), bands.likely.toInt(), bands.possible.toInt()),
                )
            val features = isoResp.features.orEmpty()
            if (features.isEmpty()) error("ORS returned empty features")

            // ORS 는 value(meter) 오름차순 3개 반환 가정. value 로 level 매핑.
            val sorted = features.sortedBy { it.properties?.value ?: 0.0 }
            val levels = listOf("CORE", "LIKELY", "POSSIBLE")
            val mapped =
                sorted.zip(levels).mapNotNull { (feature, level) ->
                    feature.geometry?.coordinates?.let {
                        ReachableResponse.OrsIsochrone.Feature(level = level, polygon = it)
                    }
                }
            val result = ReachableResponse.OrsIsochrone(features = mapped)
            runCatching {
                redisDriver.setValue(cacheKey, objectMapper.writeValueAsString(result), CACHE_TTL_SECONDS)
            }.onFailure { log.warn("ORS cache set failed", it) }
            result
        }.getOrElse { error ->
            log.warn("ORS isochrone 실패 → circle fallback: ${error.message}")
            circleFallback(post, bands)
        }
    }

    // ───── CAT: lookup 기반 보수적 반경 ─────
    private fun catCircle(post: Post): ReachableResponse {
        val likely = min(CAT_CAP_M, interpolate(CAT_LIKELY_TABLE, hoursSince(post.time)))
        return ReachableResponse.CircleFallback(
            center = ReachableResponse.CircleFallback.Center(post.lat, post.lng),
            bands =
                ReachableResponse.CircleFallback.Bands(
                    core = round2(likely * 0.3),
                    likely = round2(likely),
                    possible = round2(min(CAT_CAP_M, likely * 1.6)),
                ),
        )
    }

    // ───── OTHER: 완전 고정 ─────
    private fun otherCircle(post: Post): ReachableResponse =
        ReachableResponse.CircleFallback(
            center = ReachableResponse.CircleFallback.Center(post.lat, post.lng),
            bands =
                ReachableResponse.CircleFallback.Bands(
                    core = OTHER_CORE,
                    likely = OTHER_LIKELY,
                    possible = OTHER_POSSIBLE,
                ),
        )

    // ───── 개 fallback 원 ─────
    private fun circleFallback(
        post: Post,
        bands: DogBands,
    ): ReachableResponse =
        ReachableResponse.CircleFallback(
            center = ReachableResponse.CircleFallback.Center(post.lat, post.lng),
            bands =
                ReachableResponse.CircleFallback.Bands(
                    core = round2(bands.core),
                    likely = round2(bands.likely),
                    possible = round2(bands.possible),
                ),
        )

    // ───── 통계 lookup 기반 반경 (미터) ─────
    private fun computeDogBands(post: Post): DogBands {
        val breed = post.breedId?.let { breedRepository.findById(it).getOrNull() }
        // exploreFactor (대략 0.5~1.3) 를 ×0.7~1.3 multiplier 로 매핑.
        val factor = breed?.exploreFactor ?: 1.0
        val multiplier = max(0.7, min(1.3, 0.7 + (factor - 0.5) * 0.75))

        val likely = min(DOG_CAP_M, interpolate(DOG_LIKELY_TABLE, hoursSince(post.time)) * multiplier)
        return DogBands(
            core = likely * 0.3,
            likely = likely,
            possible = min(DOG_CAP_M, likely * 1.6),
        )
    }

    /** 두 (x,y) 점 사이 선형 보간. table 은 x 오름차순 가정. */
    private fun interpolate(
        table: List<Pair<Double, Double>>,
        x: Double,
    ): Double {
        if (x <= table.first().first) return table.first().second
        if (x >= table.last().first) return table.last().second
        for (i in 0 until table.size - 1) {
            val (x0, y0) = table[i]
            val (x1, y1) = table[i + 1]
            if (x in x0..x1) {
                val t = (x - x0) / (x1 - x0)
                return y0 + (y1 - y0) * t
            }
        }
        return table.last().second
    }

    private fun hoursSince(time: LocalDateTime): Double {
        val secs = Duration.between(time, LocalDateTime.now()).toSeconds().coerceAtLeast(0)
        return secs / 3600.0
    }

    private fun buildCacheKey(
        lat: Double,
        lng: Double,
        breedId: UUID?,
    ): String {
        // ~10m precision (소수점 4자리) + breedId 버킷팅 (null → "base")
        val latRound = round(lat * 10_000) / 10_000
        val lngRound = round(lng * 10_000) / 10_000
        val bucket = breedId?.let { "b=${floor(it.mostSignificantBits.toDouble()).toLong() % 100}" } ?: "base"
        return "$CACHE_KEY_PREFIX:$latRound:$lngRound:$bucket"
    }

    private fun round2(x: Double): Double = round(x * 100) / 100

    private data class DogBands(
        val core: Double,
        val likely: Double,
        val possible: Double,
    )
}
