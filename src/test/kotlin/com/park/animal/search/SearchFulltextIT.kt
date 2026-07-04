package com.park.animal.search

import com.park.animal.abandoned.entity.AbandonedAnimal
import com.park.animal.abandoned.repository.AbandonedAnimalRepository
import com.park.animal.breed.entity.AnimalType
import com.park.animal.common.config.JpaConfig
import com.park.animal.post.entity.MissingAnimalStatus
import com.park.animal.post.entity.Post
import com.park.animal.post.repository.PostRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 통합검색 FULLTEXT(ngram) 재활성화 검증 — 운영과 동일한 mysql:8.4 + Flyway 전체 체인(V1~V11).
 *
 * 배경: 2026-05-02 운영에서 MATCH AGAINST 가 한국어에 0건을 반환해 LIKE 로 우회했다(1d4ad98).
 * 로컬 mysql 8.4.6/8.4.10 진단으로 MySQL 계층에서는 재현 불가 확인 — 이 테스트는 앱 계층
 * (Hibernate native query + JDBC + Flyway 인덱스)까지 포함한 전체 경로가 동작함을 고정한다.
 *
 * InnoDB FTS 는 커밋된 row 만 검색되므로 테스트 트랜잭션 래핑을 끈다(NOT_SUPPORTED).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaConfig::class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SearchFulltextIT {
    @Autowired lateinit var postRepository: PostRepository

    @Autowired lateinit var abandonedAnimalRepository: AbandonedAnimalRepository

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private val registry = SimpleMeterRegistry()

    private fun controller() = SearchController(postRepository, abandonedAnimalRepository, registry)

    @BeforeEach
    fun seed() {
        if (postRepository.count() == 0L) {
            postRepository.save(
                Post(
                    authorId = UUID.randomUUID(),
                    authorName = "테스터",
                    title = "말티즈를 찾습니다",
                    phoneNum = "010-0000-0000",
                    time = LocalDateTime.now(),
                    place = "서울 강남구 역삼동",
                    gender = "남아",
                    gratuity = 0,
                    description = "강남구 역삼동에서 실종된 하얀 말티즈입니다. 겁이 많아요.",
                    lat = 37.5012,
                    lng = 127.0396,
                    openChatUrl = null,
                    missingAnimalStatus = MissingAnimalStatus.SEARCHING,
                    animalType = AnimalType.DOG,
                ),
            )
        }
        if (abandonedAnimalRepository.count() == 0L) {
            abandonedAnimalRepository.save(
                AbandonedAnimal(
                    desertionNo = "441386202600928",
                    animalType = "417000",
                    uprCd = "6280000",
                    orgCd = "3690000",
                    kindFullNm = "[개] 말티즈",
                    popfile = null,
                    sexCd = "M",
                    age = "2024(년생)",
                    weight = "3(Kg)",
                    specialMark = "치석. 순함.",
                    happenPlace = "심곡동 323-6",
                    happenDt = "20260701",
                    careNm = "부평보호소",
                    careTel = null,
                    careAddr = "인천광역시 부평구",
                    processState = "보호중",
                    noticeNo = null,
                    noticeSdt = null,
                    noticeEdt = null,
                ),
            )
        }
    }

    @Test
    @Order(1)
    fun `FULLTEXT ngram - post 한국어 phrase 매칭`() {
        val page = postRepository.searchByKeywordFulltext("\"말티즈\"", PageRequest.of(0, 20))
        assertEquals(1, page.totalElements, "V9/V11 ngram 인덱스로 한국어 매칭이 되어야 한다")
    }

    @Test
    @Order(2)
    fun `FULLTEXT ngram - abandoned_animal 한국어 phrase 매칭`() {
        val page = abandonedAnimalRepository.searchByKeywordFulltext("\"말티즈\"", PageRequest.of(0, 20))
        assertEquals(1, page.totalElements)
    }

    @Test
    @Order(3)
    fun `통합검색 - 2글자 이상은 FULLTEXT 경로로 LOST + ABANDONED 병합 반환`() {
        val res = controller().search(q = "말티즈", type = "ALL", pageNo = 1, numOfRows = 20)
        val body = res.data!!
        assertEquals(1L, body.totalLost)
        assertEquals(1L, body.totalAbandoned)
        assertEquals(setOf("LOST", "ABANDONED"), body.items.map { it.type }.toSet())
    }

    @Test
    @Order(4)
    fun `통합검색 - 1글자(ngram 미만)는 LIKE 게이트로 매칭`() {
        val res = controller().search(q = "말", type = "LOST", pageNo = 1, numOfRows = 20)
        assertEquals(1L, res.data!!.totalLost)
    }

    @Test
    @Order(99)
    fun `FULLTEXT 인덱스가 깨져도 LIKE rescue 로 결과를 보전하고 메트릭을 남긴다`() {
        // 운영 시나리오 재현: 인덱스 비정상 → MATCH 에러 → LIKE fallback
        jdbcTemplate.execute("ALTER TABLE post DROP INDEX ftx_post_search")
        jdbcTemplate.execute("ALTER TABLE abandoned_animal DROP INDEX ftx_aa_search")

        val res = controller().search(q = "말티즈", type = "ALL", pageNo = 1, numOfRows = 20)
        val body = res.data!!
        assertEquals(1L, body.totalLost, "인덱스가 없어도 LIKE rescue 로 결과가 나와야 한다")
        assertEquals(1L, body.totalAbandoned)

        val rescued =
            registry.find("fmp.search.fulltext.rescue").counters().sumOf { it.count() }
        assertTrue(rescued >= 2.0, "rescue 카운터가 소스별로 기록되어야 한다 (실제: $rescued)")
    }

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("findmypet")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("characterEncoding", "UTF-8")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                )

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            // 운영 마이그레이션 체인(V2 빈 파일 = legacy 스키마) 보완: 테스트 전용 V2.1 베이스라인 추가
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/testsupport" }
            registry.add("spring.jpa.show-sql") { "false" }
        }
    }
}
