package com.park.animal.abandoned.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.SQLDelete
import org.hibernate.type.SqlTypes
import java.util.UUID

const val ABANDONED_SUB_TABLE = "abandoned_subscription"

@Entity
@Table(
    name = ABANDONED_SUB_TABLE,
    uniqueConstraints = [
        UniqueConstraint(name = "uq_subs_user_region", columnNames = ["user_id", "upr_cd", "org_cd", "animal_type"]),
    ],
)
@SQLDelete(sql = "UPDATE $ABANDONED_SUB_TABLE SET deleted_at = NOW() WHERE id = ?")
class AbandonedSubscription(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "upr_cd", nullable = false)
    val uprCd: String,
    @Column(name = "org_cd")
    val orgCd: String? = null,
    @Column(name = "animal_type")
    val animalType: String? = null,
) : BaseEntity()
