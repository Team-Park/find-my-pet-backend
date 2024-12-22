package com.park.animal.post.entity

import com.park.animal.common.persistence.BaseEntity
import com.park.animal.post.dto.RegisterPostCommand
import jakarta.persistence.Column
import jakarta.persistence.Entity
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.SQLDelete
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

const val POST_TABLE_NAME = "post"

@Entity
@DynamicUpdate
@SQLDelete(sql = "UPDATE $POST_TABLE_NAME set deleted_at = now() WHERE id = ?")
class Post(
    @JdbcTypeCode(SqlTypes.VARCHAR)
    val authorId: UUID,
    @Column(name = "author_name")
    var authorName: String,
    @Column(name = "title")
    var title: String,
    @Column(name = "phone_num")
    var phoneNum: String,
    @Column(name = "time")
    var time: LocalDateTime,
    @Column(name = "place")
    var place: String,
    @Column(name = "gender")
    var gender: String,
    @Column(name = "gratuity")
    var gratuity: Int,
    @Column(name = "description")
    var description: String,
    @Column(name = "lat")
    var lat: Double,
    @Column(name = "lng")
    var lng: Double,
    @Column(name = "open_chat_url", nullable = true)
    var openChatUrl: String?,
) : BaseEntity() {
    companion object {
        fun createPostFromCommand(command: RegisterPostCommand): Post =
            Post(
                authorId = command.userId,
                authorName = command.userName,
                title = command.title,
                time = command.time,
                phoneNum = command.phoneNum,
                place = command.place,
                description = command.description,
                gender = command.gender,
                gratuity = command.gratuity,
                lat = command.lat,
                lng = command.lng,
                openChatUrl = command.openChatUrl,
            )
    }

    fun update(
        title: String,
        phoneNum: String,
        time: LocalDateTime,
        place: String,
        gender: String,
        gratuity: Int,
        description: String,
        lat: Double,
        lng: Double,
        openChatUrl: String?,
    ) {
        this.gender = gender
        this.time = time
        this.title = title
        this.phoneNum = phoneNum
        this.place = place
        this.gratuity = gratuity
        this.description = description
        this.lat = lat
        this.lng = lng
        this.openChatUrl = openChatUrl
    }
}
