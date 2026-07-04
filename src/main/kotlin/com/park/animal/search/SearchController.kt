package com.park.animal.search

import annotation.PublicEndPoint
import com.park.animal.abandoned.repository.AbandonedAnimalRepository
import com.park.animal.post.repository.PostRepository
import io.micrometer.core.instrument.MeterRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.dao.DataAccessException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.woo.apm.log.log
import org.woo.http.SucceededApiResponseBody
import java.time.LocalDateTime

/**
 * 실종 게시글 + 유기동물 통합 검색.
 *
 * FULLTEXT(ngram) 우선 + LIKE 안전망(하이브리드):
 * 2026-05-02 운영에서 MATCH AGAINST 가 한국어에 0건을 반환해 LIKE 로 우회했었다(1d4ad98).
 * 로컬 mysql 8.4.6/8.4.10 재현 불가 진단 후 V11 인덱스 재빌드와 함께 재활성화하며,
 * 인덱스가 다시 비정상이 되어도 사용자 영향 없이 LIKE 로 보전되고
 * `fmp_search_fulltext_rescue_total` 메트릭으로 관측된다.
 */
@RestController
@RequestMapping("/api/v1")
class SearchController(
    private val postRepository: PostRepository,
    private val abandonedAnimalRepository: AbandonedAnimalRepository,
    private val meterRegistry: MeterRegistry,
) {
    @PublicEndPoint
    @GetMapping("/search")
    @Operation(
        summary = "통합 검색",
        description = "실종 게시글(title/description/place) + 유기동물(품종/장소/보호소/특징) 검색. FULLTEXT ngram + LIKE fallback.",
    )
    fun search(
        @Parameter(description = "검색어 (1자 이상)") @RequestParam("q") q: String,
        @RequestParam("type", required = false, defaultValue = "ALL") type: String,
        @RequestParam("pageNo", required = false, defaultValue = "1") pageNo: Int,
        @RequestParam("numOfRows", required = false, defaultValue = "20") numOfRows: Int,
    ): SucceededApiResponseBody<SearchResponse> {
        val keyword = q.trim()
        if (keyword.length < 1) {
            return SucceededApiResponseBody(
                data = SearchResponse(items = emptyList(), totalLost = 0, totalAbandoned = 0, hasNextPage = false),
            )
        }

        val pageable = PageRequest.of((pageNo - 1).coerceAtLeast(0), numOfRows.coerceAtLeast(1))
        val items = mutableListOf<SearchItem>()
        var totalLost = 0L
        var totalAbandoned = 0L
        // phrase 는 요청 keyword 의 순수 함수 — 한 번만 계산해 양쪽 소스에 공유.
        // null 이면 (2글자 미만 / sanitize 후 ngram 미달) FULLTEXT 없이 LIKE 게이트.
        val phrase = toBooleanPhrase(keyword)

        if (type == "ALL" || type == "LOST") {
            val page =
                searchWithRescue(
                    phrase = phrase,
                    source = "lost",
                    fulltext = { postRepository.searchByKeywordFulltext(it, pageable) },
                    like = { postRepository.searchByKeyword(keyword, pageable) },
                )
            totalLost = page.totalElements
            items += page.content.map {
                SearchItem(
                    type = "LOST",
                    id = it.id.toString(),
                    title = it.title,
                    description = it.description,
                    place = it.place,
                    date = it.time,
                    link = "/lost/${it.id}",
                )
            }
        }

        if (type == "ALL" || type == "ABANDONED") {
            val page =
                searchWithRescue(
                    phrase = phrase,
                    source = "abandoned",
                    fulltext = { abandonedAnimalRepository.searchByKeywordFulltext(it, pageable) },
                    like = { abandonedAnimalRepository.searchByKeyword(keyword, pageable) },
                )
            totalAbandoned = page.totalElements
            items += page.content.map {
                SearchItem(
                    type = "ABANDONED",
                    id = it.desertionNo,
                    title = it.kindFullNm ?: "구조동물",
                    description = it.specialMark,
                    place = it.happenPlace,
                    thumbnail = it.popfile,
                    date = null,
                    link = "/abandonment/${it.desertionNo}",
                )
            }
        }

        // 두 결과 병합 — 단순 (LOST 먼저, ABANDONED 다음). 정교한 relevance 정렬은 후속 작업.
        return SucceededApiResponseBody(
            data =
                SearchResponse(
                    items = items,
                    totalLost = totalLost,
                    totalAbandoned = totalAbandoned,
                    hasNextPage = items.size >= numOfRows,
                ),
        )
    }

    /**
     * FULLTEXT 우선, LIKE 안전망.
     * - phrase=null (ngram token size 2 미달) → 바로 LIKE
     * - FULLTEXT 0건인데 LIKE 가 찾으면 인덱스 비정상 신호 → LIKE 결과 반환 + rescue(reason=empty)
     * - FULLTEXT 쿼리 에러(인덱스 부재 등) → LIKE 반환 + rescue(reason=error)
     */
    private fun <T> searchWithRescue(
        phrase: String?,
        source: String,
        fulltext: (String) -> Page<T>,
        like: () -> Page<T>,
    ): Page<T> {
        if (phrase == null) return like()
        return try {
            val page = fulltext(phrase)
            if (page.totalElements == 0L) {
                val fallback = like()
                if (fallback.totalElements > 0L) {
                    rescue(source, "empty")
                    fallback
                } else {
                    page
                }
            } else {
                page
            }
        } catch (e: DataAccessException) {
            log().warn("FULLTEXT search failed (source=$source) — LIKE fallback: ${e.message}")
            rescue(source, "error")
            like()
        }
    }

    /**
     * BOOLEAN MODE 연산자 제거 후 phrase 로 래핑.
     * sanitize 후 2글자 미만이면 null — ngram_token_size=2 미달 입력은 FULLTEXT 가
     * 구조적으로 0건이라 rescue(reason=empty) 가짜 경보가 되므로 LIKE 게이트로 보낸다.
     */
    private fun toBooleanPhrase(keyword: String): String? {
        val sanitized =
            keyword
                .replace(BOOLEAN_MODE_OPERATORS, " ")
                .replace(WHITESPACES, " ")
                .trim()
        if (sanitized.length < 2) return null
        return "\"$sanitized\""
    }

    private fun rescue(
        source: String,
        reason: String,
    ) {
        meterRegistry.counter("fmp.search.fulltext.rescue", "source", source, "reason", reason).increment()
    }

    data class SearchResponse(
        val items: List<SearchItem>,
        val totalLost: Long,
        val totalAbandoned: Long,
        val hasNextPage: Boolean,
    )

    data class SearchItem(
        val type: String, // LOST | ABANDONED
        val id: String,
        val title: String,
        val description: String?,
        val place: String?,
        val thumbnail: String? = null,
        val date: LocalDateTime? = null,
        val link: String,
    )

    companion object {
        private val BOOLEAN_MODE_OPERATORS = Regex("[+\\-><()~*\"@]")
        private val WHITESPACES = Regex("\\s+")
    }
}
