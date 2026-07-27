package com.park.animal.abandoned

import com.fasterxml.jackson.databind.ObjectMapper
import com.park.animal.abandoned.entity.AbandonedAnimal
import com.park.animal.abandoned.repository.AbandonedAnimalRepository
import com.park.animal.abandoned.repository.AbandonedSubscriptionRepository
import com.park.animal.common.config.JpaConfig
import com.park.animal.notification.NotificationService
import com.park.animal.publicdata.AbandonedAnimalService
import com.park.animal.publicdata.PublicDataClient
import com.park.animal.publicdata.dto.AbandonedAnimalPage
import com.park.animal.publicdata.dto.AbandonedAnimalResponse
import com.park.animal.redis.RedisDriver
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 공고기간(`notice_edt`) 만료 처리와 `noticeStatus` 필터를 못박는다.
 *
 * 2026-07 운영 상황: 상류(data.go.kr)는 진행중 7,856건만 돌려주는데 우리 미러에는 31,373건이 open 이었다.
 * stale 비율 0.7497 > `STALE_GUARD_RATIO`(0.5) 라 guard 가 매시간 발동해 `closed=0` 이 48시간 연속
 * 반복됐다 — 정리를 해야 비율이 내려가는데 비율 때문에 정리가 막히는 교착. 그 사이 발견 후 31일이 지나
 * 공고기간이 100% 끝난 항목의 96~99% 가 "보호중" 으로 노출됐다. 안락사는 공고 10일 후부터 가능하므로
 * 이용자는 이미 없는 아이 때문에 보호소에 전화하게 된다.
 *
 * 이 테스트가 지키는 것:
 * 1. 만료 판정은 `notice_edt` 로만 하고 stale guard 와 독립적이다.
 * 2. **판정 불가(NULL / 형식 불량)는 절대 만료시키지 않는다** — 살아있는 아이를 숨기지 않는다.
 * 3. 만료돼도 상세는 200 이다 — 이미 색인된 상세 URL 2만건을 404 로 만들지 않는다.
 * 4. `process_state` 를 위조하지 않는다 — "공고 종료" 는 "안락사" 가 아니다.
 * 5. 상류가 만료분을 계속 돌려줘도 다음 sync 에서 재오픈되지 않는다 (1시간 주기 진동 회귀 방어).
 *
 * 프로덕션에는 현재 closed row 가 사실상 0건이라 라이브로는 이 중 무엇도 관측할 수 없다.
 * 배포 전 근거는 이 테스트뿐이다.
 *
 * 벌크 UPDATE 를 쏘고 그 결과를 다시 읽어야 하므로 테스트 트랜잭션 래핑을 끈다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaConfig::class, AbandonedMirrorWriter::class)
@Testcontainers
class AbandonedNoticeExpiryIT {
    @Autowired lateinit var abandonedAnimalRepository: AbandonedAnimalRepository

    // 실제 Spring 빈이어야 한다. 직접 new 하면 프록시가 없어 @Transactional 이 적용되지 않고,
    // 그러면 dirty checking 이 안 먹는 프로덕션 버그를 테스트가 그대로 놓친다.
    @Autowired lateinit var mirrorWriter: AbandonedMirrorWriter

    @MockBean lateinit var subscriptionRepositoryMock: AbandonedSubscriptionRepository

    @MockBean lateinit var notificationServiceMock: NotificationService

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    // 상수로 박으면 sync 가 읽는 벽시계와 어긋나 특정 날짜부터 CI 가 깨진다.
    // (실제로 "20260728" 을 tomorrow 로 박아둔 탓에 2026-07-29 부터 실패하는 상태였다.)
    // 이제 runSync 가 today 를 주입받으므로 기준을 여기서 만들어 쓰면 실행 날짜와 무관해진다.
    private val baseDate: LocalDate = LocalDate.of(2026, 7, 27)
    private val today = baseDate.format(DateTimeFormatter.BASIC_ISO_DATE)
    private val yesterday = baseDate.minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)
    private val tomorrow = baseDate.plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)

    private fun expiryService() = AbandonedNoticeExpiryService(abandonedAnimalRepository, transactionManager)

    private fun listService() =
        AbandonedAnimalService(
            publicDataClient = mock(),
            redisDriver = mock<RedisDriver>(),
            objectMapper = ObjectMapper(),
            abandonedAnimalRepository = abandonedAnimalRepository,
        )

    private fun seed(
        desertionNo: String,
        noticeEdt: String?,
        processState: String = "보호중",
        animalType: String = "DOG",
        closedAt: java.time.LocalDateTime? = null,
        noticeSdt: String? = null,
    ): AbandonedAnimal =
        abandonedAnimalRepository.save(
            AbandonedAnimal(
                desertionNo = desertionNo,
                animalType = animalType,
                uprCd = "6110000",
                orgCd = "3220000",
                kindFullNm = "[개] 말티즈",
                popfile = null,
                sexCd = "F",
                age = "2025(년생)",
                weight = "3.2(Kg)",
                specialMark = "흰색 장모",
                happenPlace = "서울특별시 강남구 역삼동",
                happenDt = "20260401",
                careNm = "서울동물복지지원센터",
                careTel = null,
                careAddr = "서울특별시 강남구",
                processState = processState,
                noticeNo = null,
                noticeSdt = noticeSdt,
                noticeEdt = noticeEdt,
                closedAt = closedAt,
            ),
        )

    private fun reload(desertionNo: String) =
        assertNotNull(
            abandonedAnimalRepository.findByDesertionNo(desertionNo),
            "$desertionNo 가 사라졌다 — 만료는 row 를 지우는 게 아니라 closed_at 만 찍는 soft close 다",
        )

    @BeforeEach
    fun clean() {
        abandonedAnimalRepository.deleteAll()
    }

    @Test
    fun `공고기간이 지난 항목은 만료된다`() {
        seed("EXPIRED-1", noticeEdt = yesterday)

        val closed = expiryService().expireOverdueNotices(today)

        assertEquals(1, closed)
        assertNotNull(
            reload("EXPIRED-1").closedAt,
            "공고기간이 끝났는데 진행중으로 남으면 이용자가 이미 없는 아이 때문에 보호소에 연락한다",
        )
    }

    @Test
    fun `공고기간이 오늘까지인 항목은 만료되지 않는다`() {
        seed("TODAY-1", noticeEdt = today)

        assertEquals(0, expiryService().expireOverdueNotices(today))
        assertNull(reload("TODAY-1").closedAt, "공고 마지막 날은 아직 진행중이다")
    }

    @Test
    fun `공고기간이 미래인 항목은 만료되지 않는다`() {
        seed("FUTURE-1", noticeEdt = tomorrow)

        assertEquals(0, expiryService().expireOverdueNotices(today))
        assertNull(reload("FUTURE-1").closedAt, "진행중인 공고를 닫으면 보호소에 있는 아이가 목록에서 사라진다")
    }

    @Test
    fun `noticeEdt 가 없거나 형식이 깨진 항목은 만료되지 않는다`() {
        // 판정 불가를 종료로 취급하면 살아있는 아이가 조용히 사라진다. 모르면 남기는 쪽이 안전하다.
        seed("BAD-NULL", noticeEdt = null)
        seed("BAD-EMPTY", noticeEdt = "")
        seed("BAD-DASHED", noticeEdt = "2026-07-26") // 10자
        seed("BAD-8CHARS", noticeEdt = "2026-1-1") // 길이는 8이지만 숫자가 아니다
        seed("BAD-SHORT", noticeEdt = "202607")
        seed("BAD-TEXT", noticeEdt = "미정")

        val closed = expiryService().expireOverdueNotices(today)

        assertEquals(0, closed, "형식 불량/누락을 만료시키면 안 된다")
        listOf("BAD-NULL", "BAD-EMPTY", "BAD-DASHED", "BAD-8CHARS", "BAD-SHORT", "BAD-TEXT").forEach {
            assertNull(reload(it).closedAt, "$it — notice_edt 판정 불가인데 만료됐다")
        }
    }

    @Test
    fun `만료해도 process_state 는 변조되지 않는다`() {
        // "공고 종료" 는 "안락사" 가 아니다. 공고 후에도 보호소가 데리고 있는 경우(입양 대기)가 있다.
        seed("KEEP-STATE", noticeEdt = yesterday, processState = "보호중")

        expiryService().expireOverdueNotices(today)

        val row = reload("KEEP-STATE")
        assertNotNull(row.closedAt)
        assertEquals("보호중", row.processState, "우리가 아는 건 공고 기간이 끝났다는 것뿐이다 — 상태를 지어내면 안 된다")
    }

    @Test
    fun `만료된 항목은 목록에서 빠지지만 상세로는 계속 조회된다`() {
        seed("GONE-1", noticeEdt = yesterday)
        seed("ALIVE-1", noticeEdt = tomorrow)

        expiryService().expireOverdueNotices(today)

        val open = abandonedAnimalRepository.findOpenByFilters(null, null, null, PageRequest.of(0, 20))
        assertEquals(listOf("ALIVE-1"), open.content.map { it.desertionNo }, "만료 항목이 목록에 남아 있다")

        // 이미 색인된 상세 URL 이 2만건 이상이라 404 로 만들면 안 된다.
        val detail = listService().findByDesertionNo("GONE-1")
        assertNotNull(detail, "만료 항목의 상세가 사라지면 색인된 URL 2만건이 통째로 죽는다")
        assertTrue(detail.noticeClosed, "프론트가 안내 배너/noindex 를 판정할 신호가 없다")
        assertNotNull(detail.noticeClosedAt)
        assertEquals("보호중", detail.processState, "상세 응답에서도 process_state 를 위조하지 않는다")

        val alive = assertNotNull(listService().findByDesertionNo("ALIVE-1"))
        assertFalse(alive.noticeClosed)
        assertNull(alive.noticeClosedAt)
    }

    @Test
    fun `만료된 항목도 통합검색에는 나온다 - 재결합 경로를 막으면 안 된다`() {
        seed("GONE-2", noticeEdt = yesterday)
        seed("ALIVE-2", noticeEdt = tomorrow)

        expiryService().expireOverdueNotices(today)

        // 목록에서는 빠지지만(B안) 검색에서는 빠지면 안 된다.
        // 공고 기간이 끝났다고 그 아이가 보호소에서 사라진 게 아니다. 보호자가 이름으로 검색했을 때
        // 0건이 나오면 "보호소에 없구나" 하고 포기한다 — 재결합 경로를 스스로 끊는 셈이다.
        val like = abandonedAnimalRepository.searchByKeyword("말티즈", PageRequest.of(0, 20))
        assertEquals(
            setOf("ALIVE-2", "GONE-2"),
            like.content.map { it.desertionNo }.toSet(),
            "만료분이 LIKE 안전망 검색에서 사라졌다",
        )
        assertEquals(2L, like.totalElements)

        val fulltext = abandonedAnimalRepository.searchByKeywordFulltext("\"말티즈\"", PageRequest.of(0, 20))
        assertEquals(
            setOf("ALIVE-2", "GONE-2"),
            fulltext.content.map { it.desertionNo }.toSet(),
            "만료분이 FULLTEXT 검색에서 사라졌다",
        )
        // 본문과 countQuery 가 어긋나면 SearchController 의 rescue 판정이 오작동해
        // fmp_search_fulltext_rescue_total 에 가짜 경보가 뜬다 — 그 메트릭은 인덱스 비정상의 유일한 신호다.
        assertEquals(2L, fulltext.totalElements, "FULLTEXT 본문/countQuery 필터가 어긋났다")

        // 다만 화면이 구분할 수 있도록 상태는 실려 나가야 한다.
        assertNotNull(like.content.first { it.desertionNo == "GONE-2" }.closedAt)
        assertNull(like.content.first { it.desertionNo == "ALIVE-2" }.closedAt)
    }

    @Test
    fun `noticeStatus 3값이 각각 맞는 집합을 낸다`() {
        seed("S-CLOSED", noticeEdt = yesterday)
        seed("S-OPEN", noticeEdt = tomorrow)
        expiryService().expireOverdueNotices(today)

        val service = listService()

        fun ids(status: NoticeStatus) =
            runBlocking {
                service
                    .findAbandonedAnimals(
                        animalType = null,
                        pageNo = 1,
                        numOfRows = 20,
                        bgnde = null,
                        endde = null,
                        noticeStatus = status,
                    ).contents
                    .map { it.desertionNo }
                    .toSet()
            }

        assertEquals(setOf("S-OPEN"), ids(NoticeStatus.OPEN))
        assertEquals(setOf("S-CLOSED"), ids(NoticeStatus.CLOSED))
        assertEquals(setOf("S-OPEN", "S-CLOSED"), ids(NoticeStatus.ALL))
    }

    @Test
    fun `noticeStatus 기본값과 폴백은 OPEN 이다 - 파라미터 없이 부르던 기존 호출부가 깨지면 안 된다`() {
        seed("D-CLOSED", noticeEdt = yesterday)
        seed("D-OPEN", noticeEdt = tomorrow)
        expiryService().expireOverdueNotices(today)

        val defaulted =
            runBlocking {
                listService()
                    .findAbandonedAnimals(null, 1, 20, null, null)
                    .contents
                    .map { it.desertionNo }
            }
        assertEquals(listOf("D-OPEN"), defaulted, "기본값이 OPEN 이 아니면 메인 목록·지역 SSR·sitemap 이 조용히 깨진다")

        assertEquals(NoticeStatus.OPEN, NoticeStatus.from(null))
        assertEquals(NoticeStatus.OPEN, NoticeStatus.from("garbage"))
        assertEquals(NoticeStatus.OPEN, NoticeStatus.from(" open "))
        assertEquals(NoticeStatus.CLOSED, NoticeStatus.from("closed"))
        assertEquals(NoticeStatus.ALL, NoticeStatus.from("all"))
    }

    @Test
    fun `noticeStatus 필터는 animalType 지역 필터와 함께 동작한다`() {
        seed("F-DOG-CLOSED", noticeEdt = yesterday, animalType = "DOG")
        seed("F-CAT-CLOSED", noticeEdt = yesterday, animalType = "CAT")
        seed("F-CAT-OPEN", noticeEdt = tomorrow, animalType = "CAT")
        expiryService().expireOverdueNotices(today)

        val service = listService()
        val cats =
            runBlocking {
                service
                    .findAbandonedAnimals(
                        animalType = "cat",
                        pageNo = 1,
                        numOfRows = 20,
                        bgnde = null,
                        endde = null,
                        uprCd = "6110000",
                        noticeStatus = NoticeStatus.CLOSED,
                    ).contents
                    .map { it.desertionNo }
            }
        assertEquals(listOf("F-CAT-CLOSED"), cats)
    }

    @Test
    fun `배치 크기를 넘는 대량 만료도 끝까지 처리된다`() {
        // 첫 정리 대상이 2만건 이상이라 한 트랜잭션에 다 넣을 수 없다. 배치 루프가 실제로 이어 도는지 확인.
        val total = AbandonedNoticeExpiryService.BATCH_SIZE + 37
        repeat(total) { seed("BULK-$it", noticeEdt = yesterday) }

        assertEquals(total, expiryService().expireOverdueNotices(today))
        assertEquals(
            0,
            abandonedAnimalRepository.findExpiredOpenIds(today, 10).size,
            "배치 루프가 한 번만 돌고 멈추면 대량 정리가 영원히 안 끝난다",
        )
    }

    @Test
    fun `상류가 만료분을 계속 돌려줘도 재오픈되지 않는다 - 1시간 주기 진동 회귀`() {
        seed("OSC-1", noticeEdt = yesterday)
        assertEquals(1, expiryService().expireOverdueNotices(today))
        assertNotNull(reload("OSC-1").closedAt)

        // 상류는 이 항목을 여전히 "보호중" 으로 돌려준다 (실측: 발견 31일 후에도 96~99% 가 "보호중").
        runSync(upstream("OSC-1", noticeEdt = yesterday, processState = "보호중"))

        assertNotNull(
            reload("OSC-1").closedAt,
            "상류가 돌려줬다는 이유만으로 재오픈하면 만료 처리가 매시간 무효화된다(2-phase 진동)",
        )
    }

    @Test
    fun `종료 상태로 내려온 항목은 재오픈되지 않는다`() {
        seed("OSC-2", noticeEdt = tomorrow, closedAt = java.time.LocalDateTime.now())

        runSync(upstream("OSC-2", noticeEdt = tomorrow, processState = "종료(자연사)"))

        assertNotNull(reload("OSC-2").closedAt, "process_state 가 종료인데 재오픈됐다")
    }

    @Test
    fun `공고기간이 갱신되면 다시 열린다 - 만료는 되돌릴 수 있어야 한다`() {
        seed("REOPEN-1", noticeEdt = yesterday)
        expiryService().expireOverdueNotices(today)
        assertNotNull(reload("REOPEN-1").closedAt)

        // 보호소가 공고를 연장해 상류가 미래 notice_edt 로 다시 내려준 경우.
        runSync(upstream("REOPEN-1", noticeEdt = tomorrow, processState = "보호중"))

        assertNull(reload("REOPEN-1").closedAt, "공고가 실제로 다시 진행중이 되면 되살아나야 한다")
    }

    /**
     * 프로덕션과 같은 경로로 한 사이클을 돌린다 — 수집(코루틴)과 반영(프록시 경유 @Transactional)을 분리.
     *
     * 종전에는 `TransactionTemplate` 으로 감싸 `syncService.sync()` 를 직접 불렀는데, 그러면
     * 프로덕션에서 프록시가 없어 트랜잭션이 안 걸리던 사실이 테스트에서 가려진다. 여기서는
     * 실제 빈인 [mirrorWriter] 를 호출해 dirty checking 이 진짜로 동작하는지까지 검증한다.
     *
     * `today` 를 인자로 받는 이유: sync 가 벽시계를 읽으면 이 테스트가 특정 날짜부터 깨진다.
     */
    private fun runSync(
        page: AbandonedAnimalPage,
        today: String = this.today,
    ) {
        val client =
            mock<PublicDataClient> {
                onBlocking {
                    fetchAbandonedAnimals(anyOrNull(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
                } doReturn page
            }
        val syncService =
            AbandonedAnimalSyncService(
                publicDataClient = client,
                regionLookupService = mock<RegionLookupService>(),
                mirrorWriter = mirrorWriter,
                noticeExpiryService = expiryService(),
            )
        val fetched = runBlocking { syncService.fetchAll() }
        mirrorWriter.applyDiff(fetched, today)
    }

    private fun upstream(
        desertionNo: String,
        noticeEdt: String?,
        processState: String,
    ) = AbandonedAnimalPage(
        contents =
            listOf(
                AbandonedAnimalResponse(
                    desertionNo = desertionNo,
                    filename = null,
                    popfile = null,
                    kindCd = "[개] 말티즈",
                    sexCd = "F",
                    age = "2025(년생)",
                    weight = "3.2(Kg)",
                    specialMark = "흰색 장모",
                    happenPlace = "서울특별시 강남구 역삼동",
                    happenDt = "20260401",
                    careNm = "서울동물복지지원센터",
                    careTel = null,
                    careAddr = "서울특별시 강남구",
                    processState = processState,
                    noticeNo = null,
                    noticeSdt = "20260401",
                    noticeEdt = noticeEdt,
                    animalType = "DOG",
                ),
            ),
        hasNextPage = false,
        totalCount = 1,
    )


    @Test
    fun `공고기간이 법정 최소치보다 짧으면 만료 배치가 건드리지 않는다 - SQL 경로`() {
        // 실제 사례 413582202600529: 7/26 발견인데 공고종료일도 7/26 (기간 0일).
        // notice_edt < today 라 종전 쿼리는 이걸 만료시켰고, 어제 구조된 아이가 목록에서 사라졌다.
        seed("ZERO-SPAN", noticeEdt = yesterday, noticeSdt = yesterday)
        seed("SHORT-SPAN", noticeEdt = yesterday, noticeSdt = baseDate.minusDays(4).format(DateTimeFormatter.BASIC_ISO_DATE))
        seed("NORMAL-SPAN", noticeEdt = yesterday, noticeSdt = baseDate.minusDays(11).format(DateTimeFormatter.BASIC_ISO_DATE))
        seed("NO-SDT", noticeEdt = yesterday, noticeSdt = null)

        val closed = expiryService().expireOverdueNotices(today)

        assertNull(reload("ZERO-SPAN").closedAt, "공고기간 0일짜리가 만료됐다 — 상류 입력 오류를 그대로 믿었다")
        assertNull(reload("SHORT-SPAN").closedAt, "법정 최소치 미만(3일)이 만료됐다")
        assertNotNull(reload("NORMAL-SPAN").closedAt, "정상 공고(11일)는 만료돼야 한다")
        // 시작일을 모르면 교차 검증을 못 하므로 notice_edt 단독 판정으로 돌아간다.
        assertNotNull(reload("NO-SDT").closedAt, "notice_sdt 가 없으면 종전대로 판정한다")
        assertEquals(2, closed, "만료 대상은 NORMAL-SPAN 과 NO-SDT 둘뿐이다")
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
