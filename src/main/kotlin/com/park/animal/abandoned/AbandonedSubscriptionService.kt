package com.park.animal.abandoned

import com.park.animal.abandoned.entity.AbandonedSubscription
import com.park.animal.abandoned.repository.AbandonedSubscriptionRepository
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AbandonedSubscriptionService(
    private val repository: AbandonedSubscriptionRepository,
) {
    @Transactional
    fun subscribe(
        userId: UUID,
        uprCd: String,
        orgCd: String?,
        animalType: String?,
    ): AbandonedSubscription {
        repository.findByUserIdAndUprCdAndOrgCdAndAnimalTypeAndDeletedAtIsNull(userId, uprCd, orgCd, animalType)
            ?.let { return it } // 이미 존재 — idempotent
        return repository.save(
            AbandonedSubscription(
                userId = userId,
                uprCd = uprCd,
                orgCd = orgCd,
                animalType = animalType,
            ),
        )
    }

    @Transactional
    fun unsubscribe(
        userId: UUID,
        subscriptionId: UUID,
    ) {
        val sub =
            repository
                .findById(subscriptionId)
                .orElseThrow { BusinessException(ErrorCode.NOT_FOUND_BOOKMARK) }
        if (sub.userId != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        repository.delete(sub)
    }

    @Transactional(readOnly = true)
    fun list(userId: UUID): List<AbandonedSubscription> = repository.findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId)
}
