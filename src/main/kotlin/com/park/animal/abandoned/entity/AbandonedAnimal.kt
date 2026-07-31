package com.park.animal.abandoned.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

const val ABANDONED_ANIMAL_TABLE = "abandoned_animal"

@Entity
@Table(name = ABANDONED_ANIMAL_TABLE)
class AbandonedAnimal(
    @Column(name = "desertion_no", nullable = false, unique = true)
    val desertionNo: String,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "animal_type", nullable = false)
    val animalType: String,
    @Column(name = "upr_cd")
    val uprCd: String?,
    @Column(name = "org_cd")
    val orgCd: String?,
    @Column(name = "kind_full_nm")
    var kindFullNm: String?,
    @Column(name = "popfile")
    var popfile: String?,
    @Column(name = "sex_cd")
    var sexCd: String?,
    @Column(name = "age")
    var age: String?,
    @Column(name = "weight")
    var weight: String?,
    @Column(name = "special_mark")
    var specialMark: String?,
    @Column(name = "happen_place")
    var happenPlace: String?,
    @Column(name = "happen_dt")
    var happenDt: String?,
    @Column(name = "care_nm")
    var careNm: String?,
    @Column(name = "care_tel")
    var careTel: String?,
    @Column(name = "care_addr")
    var careAddr: String?,
    @Column(name = "process_state")
    var processState: String?,
    @Column(name = "notice_no")
    var noticeNo: String?,
    @Column(name = "notice_sdt")
    var noticeSdt: String?,
    @Column(name = "notice_edt")
    var noticeEdt: String?,
    @Column(name = "closed_at")
    var closedAt: LocalDateTime? = null,
) : BaseEntity() {
    fun close(at: LocalDateTime) {
        if (this.closedAt == null) this.closedAt = at
    }

    /** 응답에 다시 나타난 경우 mutable 필드 갱신. */
    fun mergeFrom(other: AbandonedAnimal) {
        this.kindFullNm = other.kindFullNm
        this.popfile = other.popfile
        this.sexCd = other.sexCd
        this.age = other.age
        this.weight = other.weight
        this.specialMark = other.specialMark
        this.happenPlace = other.happenPlace
        this.happenDt = other.happenDt
        this.careNm = other.careNm
        this.careTel = other.careTel
        this.careAddr = other.careAddr
        this.processState = other.processState
        this.noticeNo = other.noticeNo
        this.noticeSdt = other.noticeSdt
        this.noticeEdt = other.noticeEdt
    }
}
