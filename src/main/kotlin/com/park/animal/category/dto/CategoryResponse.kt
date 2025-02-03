package com.park.animal.category.dto

import com.park.animal.category.entity.Category
import java.util.UUID

data class CategoryResponse(
    val id: UUID,
    val name: String,
    val parentId: UUID?,
) {
    companion object {
        fun from(entity: Category): CategoryResponse =
            CategoryResponse(
                id = entity.id,
                name = entity.name,
                parentId = entity.parentId,
            )
    }
}
