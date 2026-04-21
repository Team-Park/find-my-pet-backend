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

        // 개 선형 모델 파라미터
        private const val PHASE1_HOURS = 72.0
        private const val PHASE2_DECAY = 0.15
        private const val DOG_MIN_M = 100.0
        private const val DOG_MAX_M = 30_000.0
        private const val FALLBACK_DOG_SPEED = 2.5
        private const val FALLBACK_DOG_FACTOR = 0.8

        // 고양이 고정 반경 (미터)
        private const val CAT_CORE = 150.0
        private const val CAT_LIKELY = 500.0
        private const val CAT_POSSIBLE = 1500.0
        private const val CAT_POSSIBLE_MAX = 3000.0

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

    // ───── CAT: 고정 반경, 7일+부터 possible 확장 ─────
    private fun catCircle(post: Post): ReachableResponse {
        val elapsedHours = hoursSince(post.time)
        val daysOverWeek = max(0.0, elapsedHours / 24 - 7)
        val possible = min(CAT_POSSIBLE_MAX, CAT_POSSIBLE + daysOverWeek * 300)
        return ReachableResponse.CircleFallback(
            center = ReachableResponse.CircleFallback.Center(post.lat, post.lng),
            bands =
                ReachableResponse.CircleFallback.Bands(
                    core = CAT_CORE,
                    likely = CAT_LIKELY,
                    possible = round2(possible),
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

    // ───── 2-Phase 반경 계산 (미터 단위) ─────
    private fun computeDogBands(post: Post): DogBands {
        val h = hoursSince(post.time)
        val breed = post.breedId?.let { breedRepository.findById(it).getOrNull() }
        val speed = breed?.baseSpeedKmh ?: FALLBACK_DOG_SPEED
        val factor = breed?.exploreFactor ?: FALLBACK_DOG_FACTOR

        val phase1 = min(h, PHASE1_HOURS) * speed * factor
        val phase2 = max(0.0, h - PHASE1_HOURS) * speed * factor * PHASE2_DECAY
        val baseKm = phase1 + phase2
        val baseM = max(DOG_MIN_M, min(DOG_MAX_M, baseKm * 1000))

        return DogBands(
            core = baseM * 0.3,
            likely = baseM * 1.0,
            possible = min(DOG_MAX_M, baseM * 1.8),
        )
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
