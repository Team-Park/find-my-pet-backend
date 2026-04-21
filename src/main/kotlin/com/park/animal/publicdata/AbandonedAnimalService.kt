package com.park.animal.publicdata

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.park.animal.publicdata.dto.AbandonedAnimalPage
import com.park.animal.redis.RedisDriver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class AbandonedAnimalService(
    private val publicDataClient: PublicDataClient,
    private val redisDriver: RedisDriver,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private const val CACHE_TTL_SECONDS = 5L * 60 // 5분 — 공공데이터 갱신 주기 고려
        private const val CACHE_KEY_PREFIX = "fmp:public-data:abandoned"

        /** 공공데이터 `upkind` 코드 매핑. */
        private val UPKIND_MAP =
            mapOf(
                "DOG" to "417000",
                "CAT" to "422400",
                "OTHER" to "429900",
            )
    }

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun findAbandonedAnimals(
        animalType: String?,
        pageNo: Int,
        numOfRows: Int,
        bgnde: String?,
        endde: String?,
    ): AbandonedAnimalPage {
        val upkind = animalType?.let { UPKIND_MAP[it.uppercase()] }
        val cacheKey = buildCacheKey(upkind, pageNo, numOfRows, bgnde, endde)

        redisDriver.getValue(cacheKey, String::class.java)?.let { cached ->
            return objectMapper.readValue(cached)
        }

        val page = publicDataClient.fetchAbandonedAnimals(upkind, pageNo, numOfRows, bgnde, endde)

        runCatching {
            redisDriver.setValue(cacheKey, objectMapper.writeValueAsString(page), CACHE_TTL_SECONDS)
        }.onFailure { log.warn("Failed to cache public data page", it) }

        return page
    }

    private fun buildCacheKey(
        upkind: String?,
        pageNo: Int,
        numOfRows: Int,
        bgnde: String?,
        endde: String?,
    ): String = "$CACHE_KEY_PREFIX:${upkind ?: "ALL"}:$pageNo:$numOfRows:${bgnde ?: "-"}:${endde ?: "-"}"
}
