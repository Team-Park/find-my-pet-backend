package com.park.animal.searchgroup.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType.STRING
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

const val SEARCH_GROUP_TEAM_TABLE_NAME = "search_group_team"

/**
 * 수색그룹 ↔ 팀 지원 연결. 양방향 요청이 가능해 대기 주체를 status 로 구분한다.
 *
 * @SQLDelete 금지 — 지원 종료 후 재지원이 UNIQUE(group_id, team_id) 에 막히면 안 된다.
 */
@Entity
@Table(
    name = SEARCH_GROUP_TEAM_TABLE_NAME,
    uniqueConstraints = [UniqueConstraint(name = "uq_sgt_group_team", columnNames = ["group_id", "team_id"])],
)
class SearchGroupTeam(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "group_id", nullable = false)
    val groupId: UUID,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "team_id", nullable = false)
    val teamId: UUID,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    var status: SearchGroupTeamStatus,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "requested_by", nullable = false)
    val requestedBy: UUID,
    @Column(name = "requested_at", nullable = false)
    var requestedAt: LocalDateTime = LocalDateTime.now(),
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "decided_by")
    var decidedBy: UUID? = null,
    @Column(name = "decided_at")
    var decidedAt: LocalDateTime? = null,
    @Column(name = "activated_at")
    var activatedAt: LocalDateTime? = null,
) : BaseEntity()
