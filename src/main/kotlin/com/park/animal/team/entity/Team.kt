package com.park.animal.team.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType.STRING
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.util.UUID

const val TEAM_TABLE_NAME = "team"

/**
 * 함께 찾기 팀. 여러 수색그룹을 동시에 지원할 수 있다.
 * @SQLDelete 를 붙이지 않는다 — 팀 해체도 status = ARCHIVED 전이다.
 */
@Entity
@Table(name = TEAM_TABLE_NAME)
class Team(
    @Column(name = "name", nullable = false, length = 30)
    var name: String,
    @Column(name = "description", length = 200)
    var description: String? = null,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    var status: TeamStatus = TeamStatus.ACTIVE,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "created_by", nullable = false)
    val createdBy: UUID,
) : BaseEntity()
