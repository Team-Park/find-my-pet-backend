package com.park.animal.breed.entity

import com.fasterxml.uuid.Generators
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType.STRING
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes
import org.jetbrains.annotations.NotNull
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "breed")
class Breed(
    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    val id: UUID = Generators.timeBasedEpochGenerator().generate(),
    @Enumerated(STRING)
    @Column(name = "animal_type", nullable = false)
    val animalType: AnimalType,
    @Column(name = "name_ko", nullable = false)
    val nameKo: String,
    @Column(name = "name_en")
    val nameEn: String?,
    @Enumerated(STRING)
    @Column(name = "size_category", nullable = false)
    val sizeCategory: SizeCategory,
    @Column(name = "base_speed_kmh")
    val baseSpeedKmh: Double?,
    @Enumerated(STRING)
    @Column(name = "behavior_pattern", nullable = false)
    val behaviorPattern: BehaviorPattern,
    @Column(name = "explore_factor", nullable = false)
    val exploreFactor: Double = 1.0,
    @Column(name = "created_at")
    @CreatedDate
    @NotNull
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at")
    @LastModifiedDate
    @NotNull
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)
