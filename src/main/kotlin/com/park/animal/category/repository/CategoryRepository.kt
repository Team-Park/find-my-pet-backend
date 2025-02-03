package com.park.animal.category.repository

import com.park.animal.category.entity.Category
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CategoryRepository : JpaRepository<Category, UUID> {
    fun findByParentId(parentId: UUID): List<Category>
}
