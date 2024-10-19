package com.park.animal.post.entity

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType.LAZY
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.SQLDelete

const val POST_IMAGE_TABLE_NAME = "post_image"

@Entity
@Table(name = POST_IMAGE_TABLE_NAME)
@SQLDelete(sql = "UPDATE $POST_IMAGE_TABLE_NAME set deleted_at = now() WHERE id = ?")
class PostImage(
    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "post_id")
    val post: Post,
    @Column(name = "image_url")
    var imageUrl: String,
) : BaseEntity()
