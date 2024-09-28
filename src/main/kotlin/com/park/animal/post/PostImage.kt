package com.park.animal.post

import com.park.animal.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType.LAZY
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne

@Entity
class PostImage(
    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "post_id")
    val post: Post,
    @Column(name = "image_url")
    var imageUrl: String,
) : BaseEntity()
