package com.park.animal.category.dto

import jakarta.annotation.Nullable
import java.util.UUID

data class RegistrationCategoryReqeust(
    val name: String,
    @Nullable
    val parentId: UUID?,
)
