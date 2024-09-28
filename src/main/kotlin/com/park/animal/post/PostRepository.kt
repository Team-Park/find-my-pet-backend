package com.park.animal.post

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PostRepository : JpaRepository<Post, UUID>, PostQueryRepository

interface PostQueryRepository
