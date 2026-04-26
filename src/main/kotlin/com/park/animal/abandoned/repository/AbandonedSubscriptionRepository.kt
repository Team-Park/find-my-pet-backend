package com.park.animal.abandoned.repository

import com.park.animal.abandoned.entity.AbandonedSubscription
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface AbandonedSubscriptionRepository : JpaRepository<AbandonedSubscription, UUID> {
    fun findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId: UUID): List<AbandonedSubscription>

    fun findByUserIdAndUprCdAndOrgCdAndAnimalTypeAndDeletedAtIsNull(
        userId: UUID,
        uprCd: String,
        orgCd: String?,
        animalType: String?,
    ): AbandonedSubscription?

    /**
     * 특정 (uprCd, orgCd, animalType) 조합의 신규 유기동물에 대해 fanout 대상 사용자 ID 를 조회.
     * 구독은 (시도 필수, 시군구/animalType 은 NULL=전체) 형태이므로 부분 매칭 OR 처리.
     */
    @Query(
        """
        SELECT s.userId FROM AbandonedSubscription s
        WHERE s.deletedAt IS NULL
          AND s.uprCd = :uprCd
          AND (s.orgCd IS NULL OR s.orgCd = :orgCd)
          AND (s.animalType IS NULL OR s.animalType = :animalType)
        """,
    )
    fun findUserIdsMatching(
        @Param("uprCd") uprCd: String,
        @Param("orgCd") orgCd: String?,
        @Param("animalType") animalType: String,
    ): List<UUID>
}
