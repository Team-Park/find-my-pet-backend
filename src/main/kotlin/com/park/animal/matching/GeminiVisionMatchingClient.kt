package com.park.animal.matching

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.park.animal.matching.dto.MatchCandidate
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostImageRepository
import com.park.animal.publicdata.AbandonedAnimalService
import com.park.animal.publicdata.dto.AbandonedAnimalResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Spring-ai Vision (`POST /api/ai/vision`) 를 호출해 실종 게시글 사진과 최근 구조동물 사진을 비교,
 * 닮은 순으로 정렬된 후보를 반환한다.
 *
 * ### 파이프라인
 * 1. `AbandonedAnimalService` 로 `animalType` 맞는 최근 14일 구조동물 목록 조회 (최대 20건)
 * 2. 각 후보의 `popfile` URL 과 실종 사진 1장을 함께 Vision API 에 넘겨 쌍별 유사도 판정
 * 3. JSON 구조로 받은 `similarity`/`reasoning` 으로 정렬 후 상위 N 개 반환
 *
 * 활성 조건: `fmp.matching.enabled=true` — default false 이면 [DummyAiMatchingClient] 가 대신 등록된다.
 */
@Component
@ConditionalOnProperty(
    prefix = "fmp.matching",
    name = ["enabled"],
    havingValue = "true",
)
class GeminiVisionMatchingClient(
    private val visionApiClient: VisionApiClient,
    private val abandonedAnimalService: AbandonedAnimalService,
    private val postImageRepository: PostImageRepository,
    private val objectMapper: ObjectMapper,
) : AiMatchingClient {
    companion object {
        private const val CANDIDATE_POOL_SIZE = 20
        private const val RECENT_DAYS = 14
        private const val APPLICATION_ID = "find-my-pet"
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        /**
         * Vision 프롬프트 — **반드시 JSON 만 반환하도록 강제**.
         * UX 지침: 유사도 숫자는 백엔드 정렬용이며 UI 에 직접 노출하지 않는다 (`prd/find-my-pet/search-radius-and-flyer.md` §FR-F12).
         */
        private val COMPARE_PROMPT =
            """
            첫 번째 이미지는 실종된 반려동물, 두 번째 이미지는 보호소에서 발견된 동물입니다.
            두 이미지가 같은 개체일 가능성을 0.0~1.0 유사도로 평가하고, 판단 근거를 2-3문장으로 설명하세요.

            반드시 아래 JSON 형식으로만 응답하세요. 다른 설명 없이 JSON 만.
            {"similarity": 0.XX, "reasoning": "..."}
            """.trimIndent()
    }

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun findSimilarCandidates(
        post: Post,
        candidateLimit: Int,
    ): List<MatchCandidate> {
        val missingPhoto = firstImageUrl(post) ?: return emptyList()

        val pool = fetchCandidatePool(post)
        if (pool.isEmpty()) return emptyList()

        val compared = pool.mapNotNull { candidate -> compareWithVision(missingPhoto, candidate, post) }
        return compared.sortedByDescending { it.similarity }.take(candidateLimit)
    }

    private suspend fun fetchCandidatePool(post: Post): List<AbandonedAnimalResponse> {
        val end = LocalDate.now()
        val begin = end.minusDays(RECENT_DAYS.toLong())
        val page =
            abandonedAnimalService.findAbandonedAnimals(
                animalType = post.animalType.name,
                pageNo = 1,
                numOfRows = CANDIDATE_POOL_SIZE,
                bgnde = begin.format(DATE_FMT),
                endde = end.format(DATE_FMT),
            )
        return page.contents.filter { !it.popfile.isNullOrBlank() }
    }

    private suspend fun compareWithVision(
        missingPhoto: String,
        candidate: AbandonedAnimalResponse,
        post: Post,
    ): MatchCandidate? {
        val candidatePhoto = candidate.popfile ?: return null
        return runCatching {
            val raw =
                visionApiClient.vision(
                    applicationId = APPLICATION_ID,
                    sessionId = "match-${post.id}-${candidate.desertionNo}",
                    prompt = COMPARE_PROMPT,
                    imageUrls = listOf(missingPhoto, candidatePhoto),
                    maxTokens = 600,
                ) ?: return@runCatching null

            val parsed = parseJsonVerdict(raw)
            MatchCandidate(
                desertionNo = candidate.desertionNo,
                photoUrl = candidate.popfile,
                kindCd = candidate.kindCd,
                sexCd = candidate.sexCd,
                age = candidate.age,
                weight = candidate.weight,
                specialMark = candidate.specialMark,
                happenPlace = candidate.happenPlace,
                happenDt = candidate.happenDt,
                careNm = candidate.careNm,
                careTel = candidate.careTel,
                careAddr = candidate.careAddr,
                similarity = parsed.similarity,
                reasoning = parsed.reasoning,
                generatedAt = LocalDateTime.now(),
            )
        }.onFailure { log.warn("Vision 비교 실패 ${candidate.desertionNo}: ${it.message}") }
            .getOrNull()
    }

    private fun parseJsonVerdict(raw: String): VisionVerdict {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end < 0 || end < start) {
            return VisionVerdict(similarity = 0.0, reasoning = raw.take(200))
        }
        val jsonSlice = raw.substring(start, end + 1)
        return runCatching { objectMapper.readValue(jsonSlice, VisionVerdict::class.java) }
            .getOrElse { VisionVerdict(similarity = 0.0, reasoning = raw.take(200)) }
    }

    private fun firstImageUrl(post: Post): String? =
        postImageRepository.findFirstByPostIdAndDeletedAtIsNullOrderByCreatedAtAsc(post.id)?.imageUrl

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VisionVerdict(
        val similarity: Double = 0.0,
        val reasoning: String? = null,
    )
}
