package com.park.animal.auth.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.FetchType.LAZY
import jakarta.persistence.OneToOne

@Entity
class UserInfo(
    @OneToOne(fetch = LAZY)
    val user: User,
    val name: String,
) : BaseEntity()
