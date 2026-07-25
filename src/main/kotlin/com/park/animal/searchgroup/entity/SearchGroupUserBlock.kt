package com.park.animal.searchgroup.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

const val SEARCH_GROUP_USER_BLOCK_TABLE_NAME = "search_group_user_block"

/**
 * 보호자의 그룹 단위 사용자 차단. unblockedAt 이 NULL 이면 차단 활성이다.
 *
 * @SQLDelete 금지 — 차단 해제 후 재차단이 UNIQUE(group_id, user_id) 에 막히면 안 된다.
 * reason 은 운영 감사용이며 보호자 전용 GET /blocks 응답 외에는 어디에도 노출하지 않는다.
 * 차단 사실 자체가 응답으로 새면 안 되므로 CTA/상세 DTO 에 isBlocked 필드를 두지 않는다(설계 16.5).
 */
@Entity
@Table(
    name = SEARCH_GROUP_USER_BLOCK_TABLE_NAME,
    uniqueConstraints = [UniqueConstraint(name = "uq_sgub_group_user", columnNames = ["group_id", "user_id"])],
)
class SearchGroupUserBlock(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "group_id", nullable = false)
    val groupId: UUID,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "blocked_by", nullable = false)
    var blockedBy: UUID,
    @Column(name = "reason", length = 500)
    var reason: String? = null,
    @Column(name = "blocked_at", nullable = false)
    var blockedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "unblocked_at")
    var unblockedAt: LocalDateTime? = null,
) : BaseEntity()
