package com.park.animal.post

import com.park.animal.common.persistence.BaseEntity
import com.park.animal.post.dto.RegisterPostCommand
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
) : BaseEntity() {
    companion object {
        fun createPostFromCommand(command: RegisterPostCommand): Post {
            return Post(
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
