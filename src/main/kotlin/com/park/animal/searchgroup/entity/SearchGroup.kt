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

const val SEARCH_GROUP_TABLE_NAME = "search_group"

/**
 * 실종 소식 1건에 붙는 수색그룹. post 와 1:1(UNIQUE post_id).
 *
 * @SQLDelete 를 붙이지 않는다. 그룹의 종료는 삭제가 아니라 status = ARCHIVED 전이이고,
 * 종료된 그룹도 이전 기록은 계속 읽을 수 있어야 한다(설계 14.1).
 */
@Entity
@Table(
    name = SEARCH_GROUP_TABLE_NAME,
    uniqueConstraints = [UniqueConstraint(name = "uq_search_group_post", columnNames = ["post_id"])],
)
class SearchGroup(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "post_id", nullable = false)
    val postId: UUID,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "join_policy", nullable = false, length = 32)
    var joinPolicy: JoinPolicy = JoinPolicy.OPEN,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    var status: SearchGroupStatus = SearchGroupStatus.ACTIVE,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "archived_reason", length = 32)
    var archivedReason: ArchivedReason? = null,
    @Column(name = "archived_at")
    var archivedAt: LocalDateTime? = null,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "archived_by")
    var archivedBy: UUID? = null,
) : BaseEntity()
