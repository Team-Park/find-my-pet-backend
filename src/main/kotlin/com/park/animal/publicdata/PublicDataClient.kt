package com.park.animal.publicdata

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.park.animal.publicdata.dto.AbandonedAnimalPage
import com.park.animal.publicdata.dto.AbandonedAnimalResponse
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * 공공데이터포털 국가동물보호정보시스템 `abandonmentPublicSrvc` API 호출 클라이언트.
 * https://www.data.go.kr/data/15098931/openapi.do
 */
@Component
class PublicDataClient(
    @Qualifier("publicDataWebClient") private val webClient: WebClient,
    @Qualifier("publicDataApiKey") private val apiKey: String,
) {
    companion object {
        const val PATH = "/abandonmentPublic_v2"
        const val SIDO_PATH = "/sido_v2"
        const val SIGUNGU_PATH = "/sigungu_v2"
        private val log = LoggerFactory.getLogger(PublicDataClient::class.java)
    }

    suspend fun fetchAbandonedAnimals(
        upkind: String?,
        pageNo: Int,
        numOfRows: Int,
        bgnde: String?,
        endde: String?,
        uprCd: String? = null,
        orgCd: String? = null,
    ): AbandonedAnimalPage {
        if (apiKey.isBlank()) {
            log.warn("publicDataApiKey is blank — /abandoned-animals will fail")
        }
        val response =
            try {
                webClient
                    .get()
                    .uri { builder ->
                        builder.path(PATH)
                        builder.queryParam("serviceKey", apiKey)
                        builder.queryParam("_type", "json")
                        builder.queryParam("numOfRows", numOfRows)
                        builder.queryParam("pageNo", pageNo)
                        upkind?.let { builder.queryParam("upkind", it) }
                        bgnde?.let { builder.queryParam("bgnde", it) }
                        endde?.let { builder.queryParam("endde", it) }
                        uprCd?.let { builder.queryParam("upr_cd", it) }
                        orgCd?.let { builder.queryParam("org_cd", it) }
                        builder.build()
                    }.retrieve()
                    .bodyToMono(PublicDataEnvelope::class.java)
                    .awaitSingle()
            } catch (e: WebClientResponseException) {
                // data.go.kr 은 auth 에러/쿼터 초과 등을 200 대신 500 + 일반 텍스트로 응답하는 케이스가 많음.
                // 원인 파악을 위해 status + body preview + 키 앞 6자 지문 만 로깅 (키 전체 노출 금지).
                val keyFp = apiKey.take(6) + "..." + "(len=${apiKey.length})"
                log.error(
                    "data.go.kr upstream error status={} body='{}' keyFingerprint={}",
                    e.statusCode,
                    e.responseBodyAsString.take(400),
                    keyFp,
                )
                throw e
            }

        val body = response.response?.body
        val items = body?.items?.item.orEmpty()
        val totalCount = body?.totalCount?.toLongOrNull() ?: 0L
        val hasNext = totalCount > (pageNo.toLong() * numOfRows)

        return AbandonedAnimalPage(
            contents = items.map { it.toDomain() },
            hasNextPage = hasNext,
            totalCount = totalCount,
        )
    }

    /** 시도(상위 행정구역) 목록. */
    suspend fun fetchSidoList(): List<RegionItem> = fetchRegions(SIDO_PATH, uprCd = null)

    /** 시군구 목록 — 상위 시도 코드 필요. */
    suspend fun fetchSigunguList(uprCd: String): List<RegionItem> = fetchRegions(SIGUNGU_PATH, uprCd = uprCd)

    private suspend fun fetchRegions(
        path: String,
        uprCd: String?,
    ): List<RegionItem> {
        if (apiKey.isBlank()) return emptyList()
        val response =
            try {
                webClient
                    .get()
                    .uri { builder ->
                        builder.path(path)
                        builder.queryParam("serviceKey", apiKey)
                        builder.queryParam("_type", "json")
                        builder.queryParam("numOfRows", 1000)
                        builder.queryParam("pageNo", 1)
                        uprCd?.let { builder.queryParam("upr_cd", it) }
                        builder.build()
                    }.retrieve()
                    .bodyToMono(RegionEnvelope::class.java)
                    .awaitSingle()
            } catch (e: WebClientResponseException) {
                log.error("region API ({}) error: status={} body='{}'", path, e.statusCode, e.responseBodyAsString.take(300))
                return emptyList()
            }
        return response.response?.body?.items?.item.orEmpty()
    }

    // -------- 공공데이터 응답 envelope 매핑 --------

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PublicDataEnvelope(
        val response: ResponseBody?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ResponseBody(
        val header: Header?,
        val body: Body?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Header(
        val resultCode: String?,
        val resultMsg: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Body(
        val items: Items?,
        val numOfRows: String?,
        val pageNo: String?,
        val totalCount: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Items(
        val item: List<RawItem>?,
    )

    // 시도/시군구 코드 응답 envelope (구조 동일, item 만 다름).
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RegionEnvelope(val response: RegionResponseBody?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RegionResponseBody(val header: Header?, val body: RegionBody?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RegionBody(val items: RegionItems?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RegionItems(val item: List<RegionItem>?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RegionItem(
        @JsonProperty("orgCd") val orgCd: String? = null,
        @JsonProperty("orgdownNm") val orgdownNm: String? = null,
        @JsonProperty("uprCd") val uprCd: String? = null,
    )

    /**
     * v2 스키마 기준 필드. v1 호환 위해 popfile/kindCd 는 v2 의 popfile1/kindFullNm 로부터 유도.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RawItem(
        val desertionNo: String,
        val filename: String? = null,
        @JsonProperty("popfile1") val popfile1: String? = null,
        @JsonProperty("popfile2") val popfile2: String? = null,
        /** v2: 코드("000200"). v1 에서는 "[개] 말티즈" 였음. */
        val kindCd: String? = null,
        /** v2 신규: "[개] 말티즈" 형태 full name. */
        @JsonProperty("kindFullNm") val kindFullNm: String? = null,
        /** v2 신규: "말티즈" */
        @JsonProperty("kindNm") val kindNm: String? = null,
        val sexCd: String?,
        val age: String?,
        val weight: String?,
        val specialMark: String?,
        val happenPlace: String?,
        val happenDt: String?,
        val careNm: String?,
        val careTel: String?,
        val careAddr: String?,
        val processState: String?,
        val noticeNo: String?,
        val noticeSdt: String?,
        val noticeEdt: String?,
        @JsonProperty("upKindCd") val upKindCd: String? = null,
        /** "경상남도 거창군" 등 관할 행정명. uprCd/orgCd 매핑 키. */
        @JsonProperty("orgNm") val orgNm: String? = null,
    ) {
        fun toDomain(): AbandonedAnimalResponse {
            val displayKind = kindFullNm ?: kindNm ?: kindCd
            // openapi.animal.go.kr 은 http/https 둘 다 동일한 파일을 서빙하지만
            // 프론트는 HTTPS 페이지이므로 mixed-content 차단 회피 위해 강제로 https 로 치환.
            val primaryPhoto = (popfile1 ?: popfile2)?.toHttps()
            return AbandonedAnimalResponse(
                desertionNo = desertionNo,
                filename = (filename ?: primaryPhoto)?.toHttps(),
                popfile = primaryPhoto,
                kindCd = displayKind,
                sexCd = sexCd,
                age = age,
                weight = weight,
                specialMark = specialMark,
                happenPlace = happenPlace,
                happenDt = happenDt,
                careNm = careNm,
                careTel = careTel,
                careAddr = careAddr,
                processState = processState,
                noticeNo = noticeNo,
                noticeSdt = noticeSdt,
                noticeEdt = noticeEdt,
                animalType = classifyAnimalType(upKindCd, displayKind),
                orgNm = orgNm,
            )
        }

        private fun String.toHttps(): String =
            if (startsWith("http://")) "https://" + substring("http://".length) else this

        /**
         * upKindCd (417000/422400/429900) 우선, 없으면 표시명 프리픽스(`[개]`/`[고양이]`)로 분류.
         */
        private fun classifyAnimalType(
            upKindCd: String?,
            displayKind: String?,
        ): String =
            when (upKindCd) {
                "417000" -> "DOG"
                "422400" -> "CAT"
                "429900" -> "OTHER"
                else ->
                    when {
                        displayKind == null -> "OTHER"
                        displayKind.startsWith("[개]") -> "DOG"
                        displayKind.startsWith("[고양이]") -> "CAT"
                        else -> "OTHER"
                    }
            }
    }
}
