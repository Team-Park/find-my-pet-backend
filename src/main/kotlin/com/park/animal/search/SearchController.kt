package com.park.animal.search

import annotation.PublicEndPoint
import com.park.animal.abandoned.repository.AbandonedAnimalRepository
import com.park.animal.post.repository.PostRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.data.domain.PageRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.woo.http.SucceededApiResponseBody
import java.time.LocalDateTime

/**
 * 실종 게시글 + 유기동물 통합 검색.
 * 단순 LIKE 쿼리 — 데이터 규모(<10k) 에선 충분. 성장 시 FULLTEXT 또는 외부 검색엔진 검토.
 */
@RestController
@RequestMapping("/api/v1")
class SearchController(
    private val postRepository: PostRepository,
    private val abandonedAnimalRepository: AbandonedAnimalRepository,
) {
    @PublicEndPoint
    @GetMapping("/search")
    @Operation(
        summary = "통합 검색",
        description = "실종 게시글(title/description/place) + 유기동물(품종/장소/보호소/특징) LIKE 검색.",
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

        // FULLTEXT + ngram 가 한국어 매칭 0 반환 이슈로 임시 비활성화.
        // 7000 row 수준에선 LIKE 로 충분 (ms 단위 응답). 추후 서버 변수 점검 후 재활성화.
        if (type == "ALL" || type == "LOST") {
            val page = postRepository.searchByKeyword(keyword, pageable)
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
            val page = abandonedAnimalRepository.searchByKeyword(keyword, pageable)
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
}
