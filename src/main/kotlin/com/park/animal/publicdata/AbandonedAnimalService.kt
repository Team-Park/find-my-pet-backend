package com.park.animal.publicdata

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.park.animal.abandoned.NoticePeriod
import com.park.animal.abandoned.NoticeStatus
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
        noticeStatus: NoticeStatus = NoticeStatus.OPEN,
    ): AbandonedAnimalPage {
        // 로컬 mirror 가 비어 있으면 (sync 전 부팅 직후) data.go.kr 직접 호출로 fallback.
        // 직결 응답에는 우리의 closed_at 개념이 아예 없으므로 OPEN 이외의 상태 조회는 fallback 대상이 아니다.
        if (noticeStatus == NoticeStatus.OPEN && abandonedAnimalRepository.count() == 0L) {
            log.info("local abandoned mirror is empty — falling back to direct data.go.kr call")
            return fetchDirect(animalType, pageNo, numOfRows, bgnde, endde, uprCd, orgCd)
        }

        val pageable = PageRequest.of((pageNo - 1).coerceAtLeast(0), numOfRows.coerceAtLeast(1))
        val type = animalType?.uppercase()
        val page =
            when (noticeStatus) {
                NoticeStatus.OPEN -> abandonedAnimalRepository.findOpenByFilters(type, uprCd, orgCd, pageable)
                NoticeStatus.CLOSED -> abandonedAnimalRepository.findClosedByFilters(type, uprCd, orgCd, pageable)
                NoticeStatus.ALL -> abandonedAnimalRepository.findAnyByFilters(type, uprCd, orgCd, pageable)
            }

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
            return normalizeDirectPage(objectMapper.readValue(cached))
        }

        val page = publicDataClient.fetchAbandonedAnimals(upkind, pageNo, numOfRows, bgnde, endde, uprCd, orgCd)

        runCatching {
            redisDriver.setValue(cacheKey, objectMapper.writeValueAsString(page), CACHE_TTL_SECONDS)
        }.onFailure { log.warn("Failed to cache public data page", it) }

        return normalizeDirectPage(page)
    }

    private fun normalizeDirectPage(page: AbandonedAnimalPage): AbandonedAnimalPage {
        val today = NoticePeriod.today()
        val open =
            page.contents.mapNotNull { item ->
                val effectiveNoticeEdt = NoticePeriod.effectiveEdt(item.noticeSdt, item.noticeEdt, item.happenDt)
                val noticeClosed =
                    item.processState?.startsWith("종료") == true ||
                        NoticePeriod.isOver(item.noticeEdt, today, item.noticeSdt, item.happenDt)
                item.copy(
                    effectiveNoticeEdt = effectiveNoticeEdt,
                    noticeClosed = noticeClosed,
                    noticeClosedAt = null,
                ).takeUnless { noticeClosed }
            }
        // 부팅 직후 fallback은 상류의 후보 페이지를 한 장씩 전달하는 edge mode다. 이 요청에서
        // CLOSED contents만 제거하되, 다음 후보 페이지를 계속 스캔할 수 있도록 상류 pagination
        // metadata(totalCount/hasNextPage)는 보존한다. 여기서 모든 상류 페이지를 재조회하지 않는다.
        return page.copy(contents = open)
    }

    /**
     * 단건 조회 — local mirror 에서 desertionNo 매칭.
     *
     * 공고가 종료된 항목도 그대로 200 으로 반환한다. 이미 색인된 상세 URL 이 2만건 이상이라
     * 404 로 만들면 안 되고, 대신 응답의 `noticeClosed` 로 프론트가 안내 배너 + noindex 를 판정한다.
     */
    fun findByDesertionNo(desertionNo: String): AbandonedAnimalResponse? =
        abandonedAnimalRepository.findByDesertionNo(desertionNo)?.let(::toResponse)

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
            effectiveNoticeEdt = NoticePeriod.effectiveEdt(a.noticeSdt, a.noticeEdt, a.happenDt),
            animalType = a.animalType,
            orgNm = null,
            noticeClosed = a.closedAt != null,
            noticeClosedAt = a.closedAt,
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
