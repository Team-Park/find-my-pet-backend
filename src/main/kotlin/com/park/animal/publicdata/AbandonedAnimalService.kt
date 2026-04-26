package com.park.animal.publicdata

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.park.animal.abandoned.entity.AbandonedAnimal
import com.park.animal.abandoned.repository.AbandonedAnimalRepository
import com.park.animal.publicdata.dto.AbandonedAnimalPage
import com.park.animal.publicdata.dto.AbandonedAnimalResponse
import com.park.animal.redis.RedisDriver
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

@Service
class AbandonedAnimalService(
    private val publicDataClient: PublicDataClient,
    private val redisDriver: RedisDriver,
    private val objectMapper: ObjectMapper,
    private val abandonedAnimalRepository: AbandonedAnimalRepository,
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

    /**
     * 진행중 유기동물 페이지네이션 조회. 백그라운드 sync(`AbandonedAnimalSyncService`) 가 채운
     * 로컬 mirror 테이블을 1순위 read.
     *
     * 부팅 직후 30s 이내처럼 mirror 가 비어 있으면 data.go.kr 직접 호출로 fallback (1회 캐시).
     * bgnde/endde 는 현재 mirror 에 happen_dt 인덱스가 없어 v1 호환 위해 단순 page 만 처리.
     */
    suspend fun findAbandonedAnimals(
        animalType: String?,
        pageNo: Int,
        numOfRows: Int,
        bgnde: String?,
        endde: String?,
        uprCd: String? = null,
        orgCd: String? = null,
    ): AbandonedAnimalPage {
        // 로컬 mirror 가 비어 있으면 (sync 전 부팅 직후) data.go.kr 직접 호출로 fallback.
        if (abandonedAnimalRepository.count() == 0L) {
            log.info("local abandoned mirror is empty — falling back to direct data.go.kr call")
            return fetchDirect(animalType, pageNo, numOfRows, bgnde, endde, uprCd, orgCd)
        }

        val pageable = PageRequest.of((pageNo - 1).coerceAtLeast(0), numOfRows.coerceAtLeast(1))
        val page =
            abandonedAnimalRepository.findOpenByFilters(
                animalType = animalType?.uppercase(),
                uprCd = uprCd,
                orgCd = orgCd,
                pageable = pageable,
            )

        return AbandonedAnimalPage(
            contents = page.content.map(::toResponse),
            hasNextPage = page.hasNext(),
            totalCount = page.totalElements,
        )
    }

    private suspend fun fetchDirect(
        animalType: String?,
        pageNo: Int,
        numOfRows: Int,
        bgnde: String?,
        endde: String?,
        uprCd: String?,
        orgCd: String?,
    ): AbandonedAnimalPage {
        val upkind = animalType?.let { UPKIND_MAP[it.uppercase()] }
        val cacheKey = buildCacheKey(upkind, pageNo, numOfRows, bgnde, endde, uprCd, orgCd)

        redisDriver.getValue(cacheKey, String::class.java)?.let { cached ->
            return objectMapper.readValue(cached)
        }

        val page = publicDataClient.fetchAbandonedAnimals(upkind, pageNo, numOfRows, bgnde, endde, uprCd, orgCd)

        runCatching {
            redisDriver.setValue(cacheKey, objectMapper.writeValueAsString(page), CACHE_TTL_SECONDS)
        }.onFailure { log.warn("Failed to cache public data page", it) }

        return page
    }

    private fun toResponse(a: AbandonedAnimal): AbandonedAnimalResponse =
        AbandonedAnimalResponse(
            desertionNo = a.desertionNo,
            filename = a.popfile,
            popfile = a.popfile,
            kindCd = a.kindFullNm,
            sexCd = a.sexCd,
            age = a.age,
            weight = a.weight,
            specialMark = a.specialMark,
            happenPlace = a.happenPlace,
            happenDt = a.happenDt,
            careNm = a.careNm,
            careTel = a.careTel,
            careAddr = a.careAddr,
            processState = a.processState,
            noticeNo = a.noticeNo,
            noticeSdt = a.noticeSdt,
            noticeEdt = a.noticeEdt,
            animalType = a.animalType,
            orgNm = null,
        )

    /** 시도 목록 — 24h 캐시. */
    suspend fun findSidoList(): List<PublicDataClient.RegionItem> {
        val key = "$CACHE_KEY_PREFIX:sido"
        redisDriver.getValue(key, String::class.java)?.let { cached ->
            return objectMapper.readValue(cached)
        }
        val list = publicDataClient.fetchSidoList()
        runCatching { redisDriver.setValue(key, objectMapper.writeValueAsString(list), 86_400L) }
        return list
    }

    /** 시군구 목록 — 시도 코드별 24h 캐시. */
    suspend fun findSigunguList(uprCd: String): List<PublicDataClient.RegionItem> {
        val key = "$CACHE_KEY_PREFIX:sigungu:$uprCd"
        redisDriver.getValue(key, String::class.java)?.let { cached ->
            return objectMapper.readValue(cached)
        }
        val list = publicDataClient.fetchSigunguList(uprCd)
        runCatching { redisDriver.setValue(key, objectMapper.writeValueAsString(list), 86_400L) }
        return list
    }

    private fun buildCacheKey(
        upkind: String?,
        pageNo: Int,
        numOfRows: Int,
        bgnde: String?,
        endde: String?,
        uprCd: String?,
        orgCd: String?,
    ): String =
        "$CACHE_KEY_PREFIX:${upkind ?: "ALL"}:$pageNo:$numOfRows:${bgnde ?: "-"}:${endde ?: "-"}" +
            ":${uprCd ?: "-"}:${orgCd ?: "-"}"
}
