package com.park.animal.abandoned

import com.park.animal.abandoned.repository.AbandonedAnimalRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

/**
 * 법정 공고기간이 끝난 항목에 `closed_at` 을 찍어 목록에서 내린다.
 *
 * 왜 sync 안이 아니라 별도 컴포넌트인가:
 * sync 의 stale close 는 "상류 응답에 없다" 는 **상류 품질에 의존하는 판정** 이라
 * `STALE_GUARD_RATIO` 로 보호받는다. 2026-07 운영에서는 상류가 7,856건만 돌려주는데 우리 미러엔
 * 31,373건이 open 으로 쌓여 stale 비율 0.75 > 0.5 로 guard 가 상시 발동, `closed=0` 이 무한 반복됐다.
 * (정리를 해야 비율이 내려가는데 비율 때문에 정리가 막히는 교착.)
 *
 * 반면 공고기간 만료는 우리가 이미 갖고 있는 `notice_edt` 만으로 판정하므로 상류 응답 품질과 무관하다.
 * 그래서 guard 밖에서, sync 트랜잭션 밖에서, sync 성공 여부와도 무관하게 돈다 — 그래야 교착을 우회한다.
 *
 * `process_state` 는 절대 건드리지 않는다. "공고 종료" 는 "안락사" 가 아니고, 공고 후에도 보호소가
 * 계속 데리고 있는 경우(입양 대기)가 있다. 상세는 계속 200 으로 살아 있고 프론트가 안내 배너를 띄운다.
 */
@Service
class AbandonedNoticeExpiryService(
    private val abandonedAnimalRepository: AbandonedAnimalRepository,
    transactionManager: PlatformTransactionManager,
) {
    companion object {
        /**
         * 한 트랜잭션에 묶는 건수.
         * 첫 정리 대상이 2만건 이상이라 단일 트랜잭션에 넣으면 락 유지 시간과 undo log/binlog 가
         * 운영 DB 를 흔든다. 반드시 끊어서 커밋한다.
         */
        const val BATCH_SIZE = 500

        /** 폭주 방지 상한 (= 100,000건/회). 넘치면 남은 건 다음 주기가 이어서 처리한다. */
        private const val MAX_BATCHES_PER_RUN = 200
    }

    private val log = LoggerFactory.getLogger(javaClass)

    /** 배치마다 독립 커밋. 호출자가 트랜잭션 안이어도 배치 경계가 유지되도록 REQUIRES_NEW. */
    private val txTemplate =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    /**
     * `notice_edt < today` 이고 아직 진행중인 항목을 배치로 close 한다.
     *
     * @param today `YYYYMMDD`. 테스트에서 고정 날짜를 주입하려고 파라미터로 열어 둔다.
     * @return 실제로 close 된 건수
     */
    fun expireOverdueNotices(today: String = NoticePeriod.today()): Int {
        var total = 0
        repeat(MAX_BATCHES_PER_RUN) {
            val closed = closeOneBatch(today)
            total += closed
            // 배치를 다 못 채웠으면 남은 대상이 없다는 뜻. 동시에 누가 close 해서 덜 찍힌 경우에도
            // 여기서 멈추는 편이 안전하다 — 다음 주기가 이어서 처리한다.
            if (closed < BATCH_SIZE) return total
        }
        log.warn("notice expiry batch cap reached — closed={} (나머지는 다음 주기에서 이어서 처리)", total)
        return total
    }

    private fun closeOneBatch(today: String): Int =
        txTemplate.execute {
            val ids = abandonedAnimalRepository.findExpiredOpenIds(today, BATCH_SIZE)
            if (ids.isEmpty()) 0 else abandonedAnimalRepository.closeByIds(ids, LocalDateTime.now())
        } ?: 0
}
