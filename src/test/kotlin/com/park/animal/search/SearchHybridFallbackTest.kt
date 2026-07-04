package com.park.animal.search

import com.park.animal.abandoned.repository.AbandonedAnimalRepository
import com.park.animal.breed.entity.AnimalType
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 하이브리드 fallback 단위 검증: FULLTEXT 가 조용히 0건을 반환하는 비정상 상태
 * (2026-05-02 운영에서 관측된 증상)에서도 LIKE 로 결과를 보전하고 메트릭을 남긴다.
 * 인덱스가 에러를 던지는 경우는 SearchFulltextIT 의 rescue 테스트가 커버한다.
 */
class SearchHybridFallbackTest {
    private val post =
        Post(
            authorId = UUID.randomUUID(),
            authorName = "테스터",
            title = "말티즈를 찾습니다",
            phoneNum = "010-0000-0000",
            time = LocalDateTime.now(),
            place = "서울 강남구",
            gender = "남아",
            gratuity = 0,
            description = "하얀 말티즈",
            lat = 37.5,
            lng = 127.0,
            openChatUrl = null,
            missingAnimalStatus = MissingAnimalStatus.SEARCHING,
            animalType = AnimalType.DOG,
        )

    @Test
    fun `FULLTEXT 가 0건을 반환하면 LIKE 로 재시도하고 rescue 메트릭을 남긴다`() {
        val postRepo = mock<PostRepository>()
        val aaRepo = mock<AbandonedAnimalRepository>()
        whenever(postRepo.searchByKeywordFulltext(any(), any())).thenReturn(Page.empty())
        whenever(postRepo.searchByKeyword(any(), any())).thenReturn(PageImpl(listOf(post)))
        whenever(aaRepo.searchByKeywordFulltext(any(), any())).thenReturn(Page.empty())
        whenever(aaRepo.searchByKeyword(any(), any())).thenReturn(Page.empty())
        val registry = SimpleMeterRegistry()

        val res = SearchController(postRepo, aaRepo, registry).search("말티즈", "ALL", 1, 20)

        assertEquals(1L, res.data!!.totalLost, "FULLTEXT 0건 → LIKE 결과로 보전되어야 한다")
        val rescueCount =
            registry
                .find("fmp.search.fulltext.rescue")
                .tag("source", "lost")
                .tag("reason", "empty")
                .counter()
                ?.count() ?: 0.0
        assertEquals(1.0, rescueCount, "reason=empty rescue 카운터가 기록되어야 한다")
    }

    @Test
    fun `FULLTEXT 와 LIKE 모두 0건이면 rescue 없이 빈 결과`() {
        val postRepo = mock<PostRepository>()
        val aaRepo = mock<AbandonedAnimalRepository>()
        whenever(postRepo.searchByKeywordFulltext(any(), any())).thenReturn(Page.empty())
        whenever(postRepo.searchByKeyword(any(), any())).thenReturn(Page.empty())
        whenever(aaRepo.searchByKeywordFulltext(any(), any())).thenReturn(Page.empty())
        whenever(aaRepo.searchByKeyword(any(), any())).thenReturn(Page.empty())
        val registry = SimpleMeterRegistry()

        val res = SearchController(postRepo, aaRepo, registry).search("없는검색어", "ALL", 1, 20)

        assertEquals(0L, res.data!!.totalLost)
        val rescued = registry.find("fmp.search.fulltext.rescue").counters().sumOf { it.count() }
        assertEquals(0.0, rescued, "정상적인 0건 검색은 rescue 로 집계되면 안 된다")
    }

    @Test
    fun `sanitize 후 1글자가 되면 FULLTEXT 를 건너뛰고 LIKE 게이트 - 가짜 rescue 신호 없음`() {
        // "말+" 는 2글자지만 sanitize 후 "말"(1글자) — ngram_token_size=2 미달이라
        // FULLTEXT 는 구조적으로 0건. rescue(reason=empty) 로 집계되면 인덱스 정상인데 가짜 경보가 된다.
        val postRepo = mock<PostRepository>()
        val aaRepo = mock<AbandonedAnimalRepository>()
        whenever(postRepo.searchByKeyword(any(), any())).thenReturn(PageImpl(listOf(post)))
        val registry = SimpleMeterRegistry()

        val res = SearchController(postRepo, aaRepo, registry).search("말+", "LOST", 1, 20)

        assertEquals(1L, res.data!!.totalLost, "LIKE 게이트로 결과가 나와야 한다")
        val rescued = registry.find("fmp.search.fulltext.rescue").counters().sumOf { it.count() }
        assertEquals(0.0, rescued, "ngram 미달 입력은 rescue 로 집계되면 안 된다")
    }

    @Test
    fun `BOOLEAN MODE 특수문자는 제거되어 phrase 로 감싸 전달된다`() {
        val postRepo = mock<PostRepository>()
        val aaRepo = mock<AbandonedAnimalRepository>()
        var captured: String? = null
        whenever(postRepo.searchByKeywordFulltext(any(), any())).thenAnswer {
            captured = it.getArgument(0)
            PageImpl(listOf(post))
        }
        val registry = SimpleMeterRegistry()

        SearchController(postRepo, aaRepo, registry).search("말티즈+@(강남)", "LOST", 1, 20)

        assertEquals("\"말티즈 강남\"", captured, "특수문자 제거 + phrase 래핑")
    }
}
