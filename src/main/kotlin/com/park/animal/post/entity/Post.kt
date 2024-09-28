package com.park.animal.post.entity

import com.park.animal.common.persistence.BaseEntity
import com.park.animal.post.dto.RegisterPostCommand
import jakarta.persistence.Entity
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

@Entity
class Post(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    val author: UUID,
    val title: String,
    val phoneNum: String,
    val time: LocalDateTime,
    val place: String,
    val gender: String,
    val gratuity: Int,
    val description: String,
) : BaseEntity() {
    companion object {
        fun createPostFromCommand(command: RegisterPostCommand): Post {
            return Post(
                author = command.userId,
                title = command.title,
                time = command.time,
                phoneNum = command.phoneNum,
                place = command.place,
                description = command.description,
                gender = command.gender,
                gratuity = command.gratuity,
            )
        }
    }
}
