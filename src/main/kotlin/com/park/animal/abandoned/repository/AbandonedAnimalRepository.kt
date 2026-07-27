package com.park.animal.abandoned.repository

import com.park.animal.abandoned.entity.AbandonedAnimal
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
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

    /**
     * 공고 종료 목록 — `noticeStatus=CLOSED` 전용. [findOpenByFilters] 와 술어만 반대다.
     *
     * 상태를 파라미터 하나로 합치지 않은 이유: OPEN 이 사실상 전체 트래픽이라
     * `closed_at IS NULL` 을 상수 술어로 유지해야 `idx_aa_region_active` / `idx_aa_open` 실행계획이
     * 지금 그대로 보존된다. 파라미터 OR 술어로 바꾸면 hot path 의 계획이 조용히 달라질 수 있다.
     */
    @Query(
        """
        SELECT a FROM AbandonedAnimal a
        WHERE a.closedAt IS NOT NULL
          AND (:animalType IS NULL OR a.animalType = :animalType)
          AND (:uprCd IS NULL OR a.uprCd = :uprCd)
          AND (:orgCd IS NULL OR a.orgCd = :orgCd)
        ORDER BY a.happenDt DESC, a.createdAt DESC
        """,
    )
    fun findClosedByFilters(
        @Param("animalType") animalType: String?,
        @Param("uprCd") uprCd: String?,
        @Param("orgCd") orgCd: String?,
        pageable: Pageable,
    ): Page<AbandonedAnimal>

    /** 공고 상태 무관 전체 — `noticeStatus=ALL` 전용. */
    /**
     * 통합검색은 **공고 종료분도 포함한다.**
     *
     * 종전에는 `closed_at IS NULL` 이 박혀 있었다. closed row 가 사실상 0건이던 동안에는 무해했지만,
     * 공고 만료 처리가 들어가면 검색 대상이 31,373 → 약 7,850(-75%)으로 떨어진다.
     * 그런데 검색은 보호자가 잃어버린 아이를 찾는 **재결합 경로의 핵심**이고, 공고 기간이 끝났다고
     * 그 아이가 보호소에서 사라진 것이 아니다. 여기서 숨기면 보호자는 "보호소에 없구나" 하고 포기한다.
     * 목록(`findOpenByFilters`)은 `noticeStatus` 로 고를 수 있지만 검색에는 그런 탈출구가 없었다.
     *
     * 대신 결과에 공고 종료 여부를 실어 화면에서 구분해 보여준다.
     */
    @Query(
        """
        SELECT a FROM AbandonedAnimal a
        WHERE (:animalType IS NULL OR a.animalType = :animalType)
          AND (:uprCd IS NULL OR a.uprCd = :uprCd)
          AND (:orgCd IS NULL OR a.orgCd = :orgCd)
        ORDER BY a.happenDt DESC, a.createdAt DESC
        """,
    )
    fun findAnyByFilters(
        @Param("animalType") animalType: String?,
        @Param("uprCd") uprCd: String?,
        @Param("orgCd") orgCd: String?,
        pageable: Pageable,
    ): Page<AbandonedAnimal>

    /**
     * 공고기간이 끝난 진행중 항목 id 배치.
     *
     * `notice_edt` 가 NULL 이거나 `YYYYMMDD` 8자리 숫자가 아니면 대상에서 제외한다 —
     * 판정 불가를 종료로 취급하면 아직 보호소에 있는 아이가 목록에서 사라진다.
     * (`CHAR_LENGTH = 8` + `^[0-9]{8}` 조합이면 전체가 숫자임이 보장된다.)
     *
     * `ORDER BY` 를 두지 않는 이유: 첫 정리에서 2만건 이상을 배치로 도는데 매 배치마다 filesort 만
     * 유발한다. `closed_at` 을 찍는 순간 다음 배치의 대상에서 빠지므로 진행은 보장된다.
     */
    @Query(
        value = """
        SELECT id FROM abandoned_animal
        WHERE closed_at IS NULL
          AND notice_edt IS NOT NULL
          AND CHAR_LENGTH(notice_edt) = 8
          AND notice_edt REGEXP '^[0-9]{8}'
          AND notice_edt < :today
          -- 공고기간이 법정 최소치(7일)보다 짧으면 상류 입력 오류로 보고 제외한다.
          -- happenDt = noticeSdt = noticeEdt 인 0일짜리 레코드가 실제로 4.6% 있고, 그대로 믿으면
          -- 어제 구조된 아이가 오늘 사라진다. notice_sdt 가 없거나 형식 불량이면 교차 검증을
          -- 할 수 없으므로 notice_edt 만으로 판정한다(= 이 조건을 통과시킨다).
          AND (
            notice_sdt IS NULL
            OR CHAR_LENGTH(notice_sdt) <> 8
            OR notice_sdt NOT REGEXP '^[0-9]{8}'
            OR DATEDIFF(STR_TO_DATE(notice_edt, '%Y%m%d'), STR_TO_DATE(notice_sdt, '%Y%m%d')) >= 7
          )
        LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findExpiredOpenIds(
        @Param("today") today: String,
        @Param("limit") limit: Int,
    ): List<String>

    /**
     * 배치 close. `process_state` 는 건드리지 않는다 — 우리가 아는 건 "공고 기간이 끝났다" 이지
     * "안락사됐다" 가 아니다. 공고 후에도 보호소가 계속 데리고 있는 경우가 있다.
     *
     * 벌크 UPDATE 라 영속성 컨텍스트를 우회하므로 실행 전후로 flush/clear 한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = "UPDATE abandoned_animal SET closed_at = :closedAt WHERE id IN (:ids) AND closed_at IS NULL",
        nativeQuery = true,
    )
    fun closeByIds(
        @Param("ids") ids: Collection<String>,
        @Param("closedAt") closedAt: LocalDateTime,
    ): Int

    @Query(
        """
        SELECT a FROM AbandonedAnimal a
        WHERE (
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

    /** FULLTEXT + ngram. 2글자 이상 쿼리. BOOLEAN MODE + phrase (호출 측에서 따옴표 래핑). */
    @Query(
        value = """
        SELECT * FROM abandoned_animal
        WHERE MATCH(kind_full_nm, happen_place, care_addr, care_nm, special_mark)
              AGAINST(:q IN BOOLEAN MODE)
        ORDER BY happen_dt DESC, created_at DESC
        """,
        countQuery = """
        SELECT COUNT(*) FROM abandoned_animal
        WHERE MATCH(kind_full_nm, happen_place, care_addr, care_nm, special_mark)
              AGAINST(:q IN BOOLEAN MODE)
        """,
        nativeQuery = true,
    )
    fun searchByKeywordFulltext(
        @Param("q") q: String,
        pageable: Pageable,
    ): Page<AbandonedAnimal>
}
