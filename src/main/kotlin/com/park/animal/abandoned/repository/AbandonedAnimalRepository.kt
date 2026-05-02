package com.park.animal.abandoned.repository

import com.park.animal.abandoned.entity.AbandonedAnimal
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface AbandonedAnimalRepository : JpaRepository<AbandonedAnimal, UUID> {
    fun findByDesertionNo(desertionNo: String): AbandonedAnimal?

    /** 진행중(closed_at IS NULL)인 desertionNo 들 — sync 시 diff 용. */
    @Query("SELECT a.desertionNo FROM AbandonedAnimal a WHERE a.closedAt IS NULL")
    fun findOpenDesertionNos(): List<String>

    @Query(
        """
        SELECT a FROM AbandonedAnimal a
        WHERE a.closedAt IS NULL
          AND (:animalType IS NULL OR a.animalType = :animalType)
          AND (:uprCd IS NULL OR a.uprCd = :uprCd)
          AND (:orgCd IS NULL OR a.orgCd = :orgCd)
        ORDER BY a.happenDt DESC, a.createdAt DESC
        """,
    )
    fun findOpenByFilters(
        @Param("animalType") animalType: String?,
        @Param("uprCd") uprCd: String?,
        @Param("orgCd") orgCd: String?,
        pageable: Pageable,
    ): Page<AbandonedAnimal>

    @Query(
        """
        SELECT a FROM AbandonedAnimal a
        WHERE a.closedAt IS NULL
          AND (
            LOWER(a.kindFullNm) LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(a.happenPlace) LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(a.careAddr) LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(a.careNm) LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(a.specialMark) LIKE LOWER(CONCAT('%', :q, '%'))
          )
        ORDER BY a.happenDt DESC, a.createdAt DESC
        """,
    )
    fun searchByKeyword(
        @Param("q") q: String,
        pageable: Pageable,
    ): Page<AbandonedAnimal>
}
