package com.park.animal.post

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Entity
import java.time.LocalDateTime

@Entity
class Post(
    val title: String,
    val phoneNum: String,
    val time: LocalDateTime,
    val place: String,
    val gender: String,
    val gratuity: Int,
    val description: String,
) : BaseEntity()
