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
    var title: String,
    var phoneNum: String,
    var time: LocalDateTime,
    var place: String,
    var gender: String,
    var gratuity: Int,
    var description: String,
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

    fun update(
        title: String,
        phoneNum: String,
        time: LocalDateTime,
        place: String,
        gender: String,
        gratuity: Int,
        description: String,
    ) {
        this.gender = gender
        this.time = time
        this.title = title
        this.phoneNum = phoneNum
        this.place = place
        this.gratuity = gratuity
        this.description = description
    }
}
