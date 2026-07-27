package com.park.animal.abandoned

import com.park.animal.abandoned.entity.AbandonedAnimal
import com.park.animal.abandoned.repository.AbandonedAnimalRepository
import com.park.animal.abandoned.repository.AbandonedSubscriptionRepository
import com.park.animal.notification.NotificationService
import com.park.animal.notification.entity.NotificationType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 상류에서 받아온 목록을 미러에 반영하는 **쓰기 전담** 빈.
 *
 * ## 왜 `AbandonedAnimalSyncService` 에서 떼어냈나
 *
 * 종전에는 수집(WebClient)과 반영(JPA)이 `@Transactional suspend fun sync()` 하나에 들어 있었고,
 * 스케줄러가 같은 객체의 `this.sync()` 를 호출했다. 두 가지가 동시에 깨져 있었다.
 *
 * 1. **`this` 호출은 Spring 프록시를 타지 않는다.** `@Transactional` 은 프록시가 붙여주는
 *    부가기능이라 자기 호출에는 아예 적용되지 않는다.
 * 2. **설령 프록시를 타도 `suspend fun` 에는 무의미하다.** `JpaTransactionManager` 는
 *    `ThreadLocal` 에 트랜잭션을 묶는데, 코루틴은 중단점마다 다른 스레드에서 재개될 수 있다.
 *
 * 그 결과 `open-in-view: false` 환경에서 `findByDesertionNo` 로 꺼낸 엔티티가 detached 였고,
 * `closedAt = null` · `close()` · `mergeFrom()` 이 **전부 조용한 no-op** 이었다. 만료를 되돌리는
 * 유일한 경로가 이 재오픈이므로, 보호소가 공고를 연장해도 그 아이는 영구히 목록에서 사라졌다.
 *
 * 그래서 **네트워크는 코루틴에서, DB 는 평범한 블로킹 트랜잭션에서** 하도록 나눴다.
 * 이 클래스의 메서드는 `suspend` 가 아니고, 다른 빈이 호출하므로 프록시를 정상적으로 경유한다.
 *
 * `today` 를 인자로 받는 이유: 벽시계를 내부에서 읽으면 테스트가 실행 날짜에 따라 깨진다.
 * 호출자가 한 번 계산해 넘기면 한 사이클 안에서 판정 기준도 일관된다.
 */
@Service
class AbandonedMirrorWriter(
    private val abandonedAnimalRepository: AbandonedAnimalRepository,
    private val subscriptionRepository: AbandonedSubscriptionRepository,
    private val notificationService: NotificationService,
) {
    companion object {
        /** 한 sync 에서 stale 비율이 이 임계를 넘고 openLocal 이 충분히 크면 close 를 건너뛴다. */
        private const val STALE_GUARD_RATIO = 0.5
        private const val STALE_GUARD_MIN_OPEN = 100
    }

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun applyDiff(
        fetched: Map<String, AbandonedAnimal>,
        today: String,
    ): SyncReport {
        val openLocal = abandonedAnimalRepository.findOpenDesertionNos().toSet()
        val fetchedKeys = fetched.keys

        val newDesertionNos = fetchedKeys - openLocal
        val staleDesertionNos = openLocal - fetchedKeys

        var inserted = 0
        var updated = 0
        var fanout = 0

        for (no in newDesertionNos) {
            val candidate = fetched[no] ?: continue
            // 이전에 close 됐다가 다시 등장한 케이스도 desertion_no UNIQUE 제약상 같은 row 다.
            val existing = abandonedAnimalRepository.findByDesertionNo(no)
            if (existing != null) {
                existing.mergeFrom(candidate)
                // 상류에 다시 나타났다는 이유만으로 무조건 재오픈하면 안 된다.
                // openLocal 은 사전 스냅샷이라 여기서 재오픈된 항목은 같은 사이클의 close 루프에
                // 들어가지 않는다 → 한 주기는 open, 다음 주기에 close, 그 다음에 또 open 하는 진동이 된다.
                // 상류가 만료분을 계속 돌려주므로 이 가드가 없으면 만료 처리가 매시간 무효화된다.
                // 공고기간이 갱신돼 실제로 다시 진행중이 된 경우에만 되살린다(만료의 되돌림 경로).
                if (!isNoticeOver(candidate, today)) existing.closedAt = null
                updated++
            } else {
                abandonedAnimalRepository.save(candidate)
                inserted++
                // 이미 공고가 끝난 항목으로 알림을 보내면 없는 아이 때문에 보호소에 연락하게 된다.
                if (!isNoticeOver(candidate, today)) fanout += notifySubscribers(candidate)
            }
        }

        // 이미 open 인데 응답에 또 있는 항목 — process_state 등이 바뀌었을 수 있다.
        for (no in fetchedKeys.intersect(openLocal)) {
            val candidate = fetched[no] ?: continue
            val existing = abandonedAnimalRepository.findByDesertionNo(no) ?: continue
            existing.mergeFrom(candidate)
            if (candidate.processState?.startsWith("종료") == true && existing.closedAt == null) {
                existing.closedAt = LocalDateTime.now(NoticePeriod.ZONE)
            }
            updated++
        }

        // stale: 응답에 없는 open → close.
        // data.go.kr 응답 이상(키 만료/스키마 변경/일시 부분 응답)으로 stale 비율이 비정상이면 건너뛴다.
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
     * 재오픈해도 되는 항목인지 판정.
     *
     * `process_state` 가 종료거나 공고기간이 이미 지났으면 진행중이 아니다.
     * `notice_edt` 판정 불가(NULL/형식 불량)는 만료로 보지 않는다 — [NoticePeriod.isOver] 참고.
     */
    private fun isNoticeOver(
        candidate: AbandonedAnimal,
        today: String,
    ): Boolean =
        candidate.processState?.startsWith("종료") == true ||
            NoticePeriod.isOver(candidate.noticeEdt, today)

    /**
     * 신규 등록 동물에 대해 매칭되는 구독자에게 알림 fanout.
     */
    private fun notifySubscribers(animal: AbandonedAnimal): Int {
        val matchedUsers =
            subscriptionRepository.findUserIdsMatching(
                uprCd = animal.uprCd ?: return 0,
                orgCd = animal.orgCd,
                animalType = animal.animalType,
            )
        if (matchedUsers.isEmpty()) return 0

        notificationService.createMany(
            userIds = matchedUsers,
            type = NotificationType.ABANDONED_NEW_IN_REGION,
            title = "관심 지역에 신규 유기동물이 등록됐어요",
            body = "${animal.kindFullNm ?: "유기동물"} (${animal.happenPlace ?: ""})",
            link = "/abandonment/${animal.desertionNo}",
        )
        return matchedUsers.size
    }

    data class SyncReport(
        val fetched: Int,
        val inserted: Int,
        val updated: Int,
        val closed: Int,
        val fanout: Int,
    )
}
