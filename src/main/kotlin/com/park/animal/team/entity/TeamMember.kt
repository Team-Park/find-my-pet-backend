package com.park.animal.team.entity

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

const val TEAM_MEMBER_TABLE_NAME = "team_member"

/**
 * 팀 멤버십.
 *
 * team_member.active_leader_key 는 매핑하지 않는다 — @Transient 도 아니고 필드 자체를 두지 않는다.
 * MySQL GENERATED ALWAYS ... STORED 컬럼이라 INSERT/UPDATE 문에 값이 포함되면 서버가
 * ERROR 3105 "The value specified for generated column 'active_leader_key' in table 'team_member'
 * is not allowed" 로 거절한다. 필드를 두면 Hibernate 가 기본적으로 INSERT 목록에 넣는다.
 * (@Generated 로 읽기 전용 매핑을 할 수도 있으나, 이 값은 애플리케이션이 읽을 일이 전혀 없고
 *  순수하게 uq_tm_single_active_leader 를 위한 DB 내부 장치이므로 매핑 자체를 생략한다.)
 *
 * @SQLDelete 금지 — 탈퇴/내보내기 후 재가입이 UNIQUE(team_id, user_id) 에 막히면 안 된다(F15).
 * 팀장 이전은 한 트랜잭션 안에서 UPDATE 두 번(강등 → 승격)으로 한다. CASE WHEN 단일 UPDATE 는
 * uq_tm_single_active_leader 가 행 단위로 검사돼 순서에 따라 1062 가 난다.
 */
@Entity
@Table(
    name = TEAM_MEMBER_TABLE_NAME,
    uniqueConstraints = [UniqueConstraint(name = "uq_tm_team_user", columnNames = ["team_id", "user_id"])],
)
class TeamMember(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "team_id", nullable = false)
    val teamId: UUID,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "user_name", length = 64)
    var userName: String? = null,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 32)
    var role: TeamRole,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    var status: TeamMemberStatus,
    @Column(name = "joined_at")
    var joinedAt: LocalDateTime? = null,
    @Column(name = "requested_at")
    var requestedAt: LocalDateTime? = null,
    @Column(name = "decided_at")
    var decidedAt: LocalDateTime? = null,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "decided_by")
    var decidedBy: UUID? = null,
) : BaseEntity()
