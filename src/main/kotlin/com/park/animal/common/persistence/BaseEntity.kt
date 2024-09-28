package com.park.animal.common.persistence

import com.fasterxml.uuid.Generators
import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes
import org.jetbrains.annotations.NotNull
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime
import java.util.*

@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseEntity {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    val id: UUID = Generators.timeBasedEpochGenerator().generate()

    @Column(name = "created_at")
    @CreatedDate
    @NotNull
    lateinit var createdAt: LocalDateTime

    @Column(name = "updated_at")
    @LastModifiedDate
    @NotNull
    lateinit var updatedAt: LocalDateTime

    @Column(name = "deleted_at", nullable = true)
    var deletedAt: LocalDateTime? = null
}
