package com.park.animal.flyer.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType.STRING
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.SQLDelete
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

const val FLYER_LOCATION_TABLE_NAME = "flyer_location"

@Entity
@Table(name = FLYER_LOCATION_TABLE_NAME)
@SQLDelete(sql = "UPDATE $FLYER_LOCATION_TABLE_NAME SET deleted_at = NOW() WHERE id = ?")
class FlyerLocation(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "post_id", nullable = false)
    val postId: UUID,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "posted_by", nullable = false)
    val postedBy: UUID,
    @Column(name = "lat", nullable = false)
    val lat: Double,
    @Column(name = "lng", nullable = false)
    val lng: Double,
    @Column(name = "note")
    var note: String? = null,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false)
    var status: FlyerStatus = FlyerStatus.POSTED,
    @Enumerated(STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "visibility", nullable = false)
    var visibility: FlyerVisibility = FlyerVisibility.PRIVATE,
    @Column(name = "posted_at", nullable = false)
    val postedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "collected_at")
    var collectedAt: LocalDateTime? = null,
) : BaseEntity() {
    fun toggle(newStatus: FlyerStatus) {
        this.status = newStatus
        this.collectedAt = if (newStatus == FlyerStatus.COLLECTED) LocalDateTime.now() else null
    }

    fun updateVisibility(v: FlyerVisibility) {
        this.visibility = v
    }
}
