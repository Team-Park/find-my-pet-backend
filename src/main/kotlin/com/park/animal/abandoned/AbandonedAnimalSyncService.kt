package com.park.animal.abandoned

import com.park.animal.abandoned.entity.AbandonedAnimal
import com.park.animal.publicdata.PublicDataClient
import com.park.animal.publicdata.dto.AbandonedAnimalResponse
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * 공공데이터 유기동물 목록을 미러로 끌어오는 스케줄러.
 *
 * **네트워크 수집만 담당한다.** DB 반영은 [AbandonedMirrorWriter], 공고 만료는
 * [AbandonedNoticeExpiryService] 가 각각 별도 빈으로 처리한다.
 *
 * 이렇게 나눈 이유는 [AbandonedMirrorWriter] 의 주석에 적었다 — 요약하면 `@Transactional` 은
 * (1) 자기 호출(`this.sync()`)에는 프록시가 없어 적용되지 않고, (2) `suspend fun` 에서는
 * 트랜잭션이 `ThreadLocal` 에 묶이므로 코루틴 스레드 전환과 함께 무의미해진다.
 * 그래서 수집은 코루틴, 반영은 평범한 블로킹 트랜잭션으로 분리했다.
 */
@Service
class AbandonedAnimalSyncService(
    private val publicDataClient: PublicDataClient,
    private val regionLookupService: RegionLookupService,
    private val mirrorWriter: AbandonedMirrorWriter,
    private val noticeExpiryService: AbandonedNoticeExpiryService,
) {
    companion object {
        private const val PAGE_SIZE = 500
        // 안전 상한 — 보호중 표본이 25,000 이상이면 별도 검토. 응답 totalCount 따라 동적으로 더 작게 조정됨.
        private const val MAX_PAGES = 50
    }

    private val log = LoggerFactory.getLogger(javaClass)

    /** 1시간 주기 sync. 첫 실행은 부팅 후 30초 뒤 (local DB 빠르게 채우기). */
    @Scheduled(initialDelay = 30_000, fixedDelay = 60L * 60_000)
    fun runSync() {
        // 판정 기준 날짜는 한 사이클에 한 번만 읽는다. 수집이 오래 걸려 자정을 넘기면
        // 앞뒤 항목이 다른 날짜로 판정돼 일관성이 깨진다.
        val today = NoticePeriod.today()

        try {
            // 네트워크만 코루틴에서. 이 구간에는 트랜잭션도 DB 커넥션도 잡지 않는다.
            val snapshot = runBlocking { fetchAll() }
            if (!snapshot.complete) {
                log.warn(
                    "abandoned upstream snapshot incomplete — expected={} received={} unique={}. stale close skipped.",
                    snapshot.expectedCount,
                    snapshot.receivedCount,
                    snapshot.animals.size,
                )
            }
            // 반영은 다른 빈의 논-suspend @Transactional — 프록시를 정상 경유한다.
            val report =
                mirrorWriter.applyDiff(
                    fetched = snapshot.animals,
                    today = today,
                    allowStaleClose = snapshot.complete,
                )
            log.info(
                "abandoned sync done: fetched={} complete={} inserted={} updated={} closed={} fanout={}",
                report.fetched,
                snapshot.complete,
                report.inserted,
                report.updated,
                report.closed,
                report.fanout,
            )
        } catch (e: Exception) {
            log.error("abandoned sync failed", e)
        }

        // 공고 만료는 상류 응답이 아니라 저장된 공고일·발견일의 canonical 실효 종료일로 판정하므로
        // sync 성공 여부·stale guard 와 무관하게 항상 돌아야 한다.
        try {
            val expired = noticeExpiryService.expireOverdueNotices(today)
            if (expired > 0) log.info("abandoned notice expiry: closed={}", expired)
        } catch (e: Exception) {
            log.error("abandoned notice expiry failed", e)
        }
    }

    /**
     * 상류 전 페이지를 모아 스냅샷으로 돌려준다. DB 를 건드리지 않는다.
     *
     * stale 종료는 "이번 전체 스냅샷에 없다"는 사실에만 의존해야 한다. `totalCount` 누락/변경,
     * 중간 빈 페이지, 조기 `hasNextPage=false`, 안전 상한 절단, 중복 ID 중 하나라도 있으면
     * [Snapshot.complete]를 false로 내려 기존 OPEN 행의 stale 종료를 금지한다. 수집된 개별 행의
     * insert/update/명시 종료/실효 만료 반영은 계속 가능하다.
     */
    suspend fun fetchAll(): Snapshot {
        val fetched = mutableMapOf<String, AbandonedAnimal>()
        val firstPage =
            publicDataClient.fetchAbandonedAnimals(
                upkind = null,
                pageNo = 1,
                numOfRows = PAGE_SIZE,
                bgnde = null,
                endde = null,
            )
        firstPage.contents.forEach { fetched[it.desertionNo] = toEntity(it) }
        var receivedCount = firstPage.contents.size
        val expectedCount = firstPage.totalCount

        val expectedPages =
            if (expectedCount > 0) {
                ((expectedCount + PAGE_SIZE - 1) / PAGE_SIZE).toInt()
            } else {
                1
            }
        val totalPages = expectedPages.coerceAtMost(MAX_PAGES)
        var complete = expectedCount > 0 && expectedPages <= MAX_PAGES
        if (firstPage.contents.isEmpty() && expectedCount > 0) complete = false
        if (firstPage.hasNextPage != (expectedPages > 1)) complete = false
        log.debug(
            "abandoned sync — totalCount={} → expectedPages={} fetchPages={}",
            expectedCount,
            expectedPages,
            totalPages,
        )

        for (page in 2..totalPages) {
            val resp =
                publicDataClient.fetchAbandonedAnimals(
                    upkind = null,
                    pageNo = page,
                    numOfRows = PAGE_SIZE,
                    bgnde = null,
                    endde = null,
                )
            if (resp.totalCount != expectedCount) complete = false
            if (resp.contents.isEmpty()) {
                complete = false
                break
            }
            receivedCount += resp.contents.size
            resp.contents.forEach { item -> fetched[item.desertionNo] = toEntity(item) }
            val expectedMore = page < expectedPages
            if (resp.hasNextPage != expectedMore) complete = false
            if (!resp.hasNextPage && expectedMore) break
        }

        if (receivedCount.toLong() != expectedCount) complete = false
        if (fetched.size.toLong() != expectedCount) complete = false
        return Snapshot(
            animals = fetched,
            complete = complete,
            expectedCount = expectedCount,
            receivedCount = receivedCount,
        )
    }

    data class Snapshot(
        val animals: Map<String, AbandonedAnimal>,
        val complete: Boolean,
        val expectedCount: Long,
        val receivedCount: Int,
    )

    private fun toEntity(r: AbandonedAnimalResponse): AbandonedAnimal {
        val region = regionLookupService.lookup(r.orgNm)
        return AbandonedAnimal(
            desertionNo = r.desertionNo,
            animalType = r.animalType ?: "OTHER",
            uprCd = region?.uprCd,
            orgCd = region?.orgCd,
            kindFullNm = r.kindCd,
            popfile = r.popfile,
            sexCd = r.sexCd,
            age = r.age,
            weight = r.weight,
            specialMark = r.specialMark,
            happenPlace = r.happenPlace,
            happenDt = r.happenDt,
            careNm = r.careNm,
            careTel = r.careTel,
            careAddr = r.careAddr,
            processState = r.processState,
            noticeNo = r.noticeNo,
            noticeSdt = r.noticeSdt,
            noticeEdt = r.noticeEdt,
        )
    }

}
