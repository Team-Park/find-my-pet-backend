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

const val SEARCH_GROUP_MEMBER_TABLE_NAME = "search_group_member"

/**
 * 수색그룹 직접 참여 멤버십.
 *
 * @SQLDelete 금지: soft-delete + UNIQUE(group_id, user_id) 조합은 재가입을 영구히 막는다.
 * post_bookmark 에서 실제로 터진 버그다(F15). 탈퇴/내보내기는 status 전이이고, 재가입은
 * 같은 행을 ACTIVE 로 되돌리는 것이다. 따라서 deleted_at 은 항상 NULL 로 남는다.
 *
 * userName 은 표시용 비정규화 값이다. 컨트롤러에서 passport.requireUserContext() 를 직접
 * 부르지 않고 runCatching 으로 얻어 이 컬럼에 저장한다.
 */
@Entity
@Table(
    name = SEARCH_GROUP_MEMBER_TABLE_NAME,
    uniqueConstraints = [UniqueConstraint(name = "uq_sgm_group_user", columnNames = ["group_id", "user_id"])],
)
class SearchGroupMember(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "group_id", nullable = false)
    val groupId: UUID,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "user_name", length = 64)
    var userName: String? = null,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    var status: SearchGroupMemberStatus,
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
