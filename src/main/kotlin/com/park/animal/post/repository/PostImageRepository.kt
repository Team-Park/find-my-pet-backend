package com.park.animal.post.repository

import com.park.animal.post.entity.PostImage
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PostImageRepository : JpaRepository<PostImage, UUID>
