package com.park.animal.searchgroup.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType.STRING
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.util.UUID

const val SEARCH_GROUP_EVENT_TABLE_NAME = "search_group_event"

/**
 * 그룹 감사 기록(설계 20) + 활동 탭(설계 10)의 원본.
 *
 * detail 에는 상태 전이 요약만 넣는다. 메시지 본문, 상세 좌표, 전화번호, 차단 사유는
 * 절대 저장하지 않는다(설계 16.5). @SQLDelete 금지 — 감사 기록은 지우지 않는다.
 */
@Entity
@Table(name = SEARCH_GROUP_EVENT_TABLE_NAME)
class SearchGroupEvent(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "group_id", nullable = false)
    val groupId: UUID,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", nullable = false, length = 64)
    val type: SearchGroupEventType,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "actor_id")
    val actorId: UUID? = null,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_id")
    val targetId: UUID? = null,
    @Column(name = "detail", length = 256)
    val detail: String? = null,
) : BaseEntity()
