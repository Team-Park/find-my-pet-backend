package com.park.animal.post.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

@Document("post")
class Review(
    @Id
    val id: String? = null,
    val authorId: String,
    val authorName: String,
    val title: String,
    val content: String,
    val categoryId: String? = null,
    @CreatedDate
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @LastModifiedDate
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    var dynamicFields: MutableMap<String, Any> = mutableMapOf(),
) {
    companion object {
        fun create(
            authorId: String,
            authorName: String,
            title: String,
            content: String,
            fields: Map<String, Any>,
            categoryId: String,
        ): Review =
            Review(
                authorId = authorId,
                authorName = authorName,
                dynamicFields = fields.toMutableMap(),
                title = title,
                content = content,
                categoryId = categoryId,
            )
    }
}
