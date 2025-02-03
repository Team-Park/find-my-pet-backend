package com.park.animal.category

import com.park.animal.category.dto.CategoryResponse
import com.park.animal.category.entity.Category
import com.park.animal.category.repository.CategoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CategoryService(
    val categoryRepository: CategoryRepository,
) {
    @Transactional
    fun register(
        name: String,
        parentId: UUID?,
    ) {
        val category = Category(name = name, parentId = parentId)
        categoryRepository.save(category)
    }

    @Transactional(readOnly = true)
    fun findByAll(): List<CategoryResponse> =
        categoryRepository.findAll().map {
            CategoryResponse.from(it)
        }

    @Transactional(readOnly = true)
    fun findByParentId(parentId: UUID): List<CategoryResponse> =
        categoryRepository.findByParentId(parentId).map {
            CategoryResponse.from(it)
        }
}
