package com.park.animal.sighting.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.SQLDelete
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

const val SIGHTING_TABLE_NAME = "sighting"

@Entity
@Table(name = SIGHTING_TABLE_NAME)
@SQLDelete(sql = "UPDATE $SIGHTING_TABLE_NAME SET deleted_at = NOW() WHERE id = ?")
class Sighting(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "post_id", nullable = false)
    val postId: UUID,
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "reporter_id", nullable = false)
    val reporterId: UUID,
    @Column(name = "reporter_name")
    val reporterName: String?,
    @Column(name = "lat", nullable = false)
    val lat: Double,
    @Column(name = "lng", nullable = false)
    val lng: Double,
    @Column(name = "sighted_at", nullable = false)
    val sightedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "note")
    val note: String?,
    @Column(name = "photo_url")
    val photoUrl: String?,
) : BaseEntity()
