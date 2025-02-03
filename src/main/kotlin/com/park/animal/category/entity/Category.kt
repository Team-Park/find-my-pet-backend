package com.park.animal.category.entity

import com.fasterxml.uuid.Generators
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes
import org.jetbrains.annotations.NotNull
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import java.time.LocalDateTime
import java.util.UUID

@Entity
class Category(
    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    val id: UUID = Generators.timeBasedEpochGenerator().generate(),
    @Column(name = "created_at")
    @CreatedDate
    @NotNull
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at")
    @LastModifiedDate
    @NotNull
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "name")
    val name: String,
    @Column(name = "parent_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    val parentId: UUID?,
)
