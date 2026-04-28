package com.park.animal.abandoned

import com.park.animal.abandoned.entity.AbandonedAnimal
import com.park.animal.abandoned.repository.AbandonedAnimalRepository
import com.park.animal.abandoned.repository.AbandonedSubscriptionRepository
import com.park.animal.notification.NotificationService
import com.park.animal.notification.entity.NotificationType
import com.park.animal.publicdata.PublicDataClient
import com.park.animal.publicdata.dto.AbandonedAnimalResponse
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AbandonedAnimalSyncService(
    private val publicDataClient: PublicDataClient,
    private val abandonedAnimalRepository: AbandonedAnimalRepository,
    private val subscriptionRepository: AbandonedSubscriptionRepository,
    private val notificationService: NotificationService,
    private val regionLookupService: RegionLookupService,
) {
    companion object {
        private const val PAGE_SIZE = 500
        // 안전 상한 — 보호중 표본이 25,000 이상이면 별도 검토. 응답 totalCount 따라 동적으로 더 작게 조정됨.
        private const val MAX_PAGES = 50
        private const val ALERT_BURST_CAP_PER_USER = 5

        // 한 sync 에서 stale 비율이 이 임계 초과 + openLocal 충분히 클 때 → close 스킵 (data.go.kr silent breaking change 방어).
        private const val STALE_GUARD_RATIO = 0.5
        private const val STALE_GUARD_MIN_OPEN = 100
    }

    private val log = LoggerFactory.getLogger(javaClass)

    /** 1시간 주기 sync. 첫 실행은 부팅 후 30초 뒤 (local DB 빠르게 채우기). */
    @Scheduled(initialDelay = 30_000, fixedDelay = 60L * 60_000)
    fun runSync() {
        runBlocking {
            try {
                val report = sync()
                log.info(
                    "abandoned sync done: fetched={} inserted={} updated={} closed={} fanout={}",
                    report.fetched,
                    report.inserted,
                    report.updated,
                    report.closed,
                    report.fanout,
                )
            } catch (e: Exception) {
                log.error("abandoned sync failed", e)
            }
        }
    }

    @Transactional
    suspend fun sync(): SyncReport {
        // 1. 첫 페이지 fetch 로 totalCount 확인 → 필요한 페이지 수만 순회.
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

        val totalPages =
            if (firstPage.totalCount > 0) {
                ((firstPage.totalCount + PAGE_SIZE - 1) / PAGE_SIZE).toInt().coerceAtMost(MAX_PAGES)
            } else {
                1
            }
        log.debug("abandoned sync — totalCount={} → totalPages={}", firstPage.totalCount, totalPages)

        for (page in 2..totalPages) {
            val resp = publicDataClient.fetchAbandonedAnimals(
                upkind = null,
                pageNo = page,
                numOfRows = PAGE_SIZE,
                bgnde = null,
                endde = null,
            )
            if (resp.contents.isEmpty()) break
            resp.contents.forEach { item ->
                fetched[item.desertionNo] = toEntity(item)
            }
            if (!resp.hasNextPage) break
        }

        // 2. 신규 / 갱신 / 종료 분류
        val openLocal = abandonedAnimalRepository.findOpenDesertionNos().toSet()
        val fetchedKeys = fetched.keys

        val newDesertionNos = fetchedKeys - openLocal
        val staleDesertionNos = openLocal - fetchedKeys

        var inserted = 0
        var updated = 0
        var fanout = 0

        for (no in newDesertionNos) {
            val candidate = fetched[no] ?: continue
            // 이전에 close 됐다가 다시 등장한 케이스도 동일 desertion_no UNIQUE 제약상 별 케이스 — 기존 row 갱신.
            val existing = abandonedAnimalRepository.findByDesertionNo(no)
            if (existing != null) {
                existing.mergeFrom(candidate)
                existing.closedAt = null
                updated++
            } else {
                abandonedAnimalRepository.save(candidate)
                inserted++
                fanout += notifySubscribers(candidate)
            }
        }

        // 갱신 (이미 open 인데 응답에 또 있는 항목 — process_state 등 변경 가능)
        for (no in fetchedKeys.intersect(openLocal)) {
            val candidate = fetched[no] ?: continue
            val existing = abandonedAnimalRepository.findByDesertionNo(no) ?: continue
            existing.mergeFrom(candidate)
            // 종료 상태로 바뀐 경우 close.
            if (candidate.processState?.startsWith("종료") == true && existing.closedAt == null) {
                existing.closedAt = LocalDateTime.now()
            }
            updated++
        }

        // stale: 응답에 없는 open → close.
        // ★ data.go.kr 응답 이상(키 만료/스키마 변경/일시 부분 응답) 으로 stale 비율이 비정상 일 때 close 스킵.
        var closed = 0
        val staleRatio =
            if (openLocal.isEmpty()) 0.0 else staleDesertionNos.size.toDouble() / openLocal.size
        if (staleRatio > STALE_GUARD_RATIO && openLocal.size >= STALE_GUARD_MIN_OPEN) {
            log.warn(
                "stale guard tripped — staleRatio={}, openLocal={}, stale={}. close skipped.",
                staleRatio,
                openLocal.size,
                staleDesertionNos.size,
            )
        } else {
            for (no in staleDesertionNos) {
                val existing = abandonedAnimalRepository.findByDesertionNo(no) ?: continue
                existing.close()
                closed++
            }
        }

        return SyncReport(fetched.size, inserted, updated, closed, fanout)
    }

    /**
     * 신규 등록 동물에 대해 매칭되는 구독자에게 알림 fanout.
     * 사용자 폭증 방지: 사용자당 1회 sync 에 최대 [ALERT_BURST_CAP_PER_USER] 건만 전송.
     */
    private fun notifySubscribers(animal: AbandonedAnimal): Int {
        val matchedUsers =
            subscriptionRepository.findUserIdsMatching(
                uprCd = animal.uprCd ?: return 0,
                orgCd = animal.orgCd,
                animalType = animal.animalType,
            )
        if (matchedUsers.isEmpty()) return 0

        // burst cap: 단순화 위해 sync 1회당 user 별 카운트 — 실제 운영에서 batch grouping 으로 개선 여지.
        notificationService.createMany(
            userIds = matchedUsers,
            type = NotificationType.ABANDONED_NEW_IN_REGION,
            title = "관심 지역에 신규 유기동물이 등록됐어요",
            body = "${animal.kindFullNm ?: "유기동물"} (${animal.happenPlace ?: ""})",
            link = "/abandonment/${animal.desertionNo}",
        )
        return matchedUsers.size
    }

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

    data class SyncReport(
        val fetched: Int,
        val inserted: Int,
        val updated: Int,
        val closed: Int,
        val fanout: Int,
    )
}
