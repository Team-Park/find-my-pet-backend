package com.park.animal.publicdata

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.park.animal.publicdata.dto.AbandonedAnimalPage
import com.park.animal.publicdata.dto.AbandonedAnimalResponse
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

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
        const val PATH = "/abandonmentPublic"
    }

    suspend fun fetchAbandonedAnimals(
        upkind: String?,
        pageNo: Int,
        numOfRows: Int,
        bgnde: String?,
        endde: String?,
    ): AbandonedAnimalPage {
        val response =
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
                    builder.build()
                }.retrieve()
                .bodyToMono(PublicDataEnvelope::class.java)
                .awaitSingle()

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RawItem(
        val desertionNo: String,
        val filename: String?,
        val popfile: String?,
        val kindCd: String?,
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
    ) {
        fun toDomain(): AbandonedAnimalResponse =
            AbandonedAnimalResponse(
                desertionNo = desertionNo,
                filename = filename,
                popfile = popfile,
                kindCd = kindCd,
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
                animalType = classifyAnimalType(upKindCd, kindCd),
            )

        /**
         * 공공데이터 upKindCd (417000/422400/429900) 또는 kindCd 프리픽스로 분류.
         * 원본 `[개] 말티즈` 패턴 → `DOG`.
         */
        private fun classifyAnimalType(
            upKindCd: String?,
            kindCd: String?,
        ): String =
            when (upKindCd) {
                "417000" -> "DOG"
                "422400" -> "CAT"
                "429900" -> "OTHER"
                else ->
                    when {
                        kindCd == null -> "OTHER"
                        kindCd.startsWith("[개]") -> "DOG"
                        kindCd.startsWith("[고양이]") -> "CAT"
                        else -> "OTHER"
                    }
            }
    }
}
