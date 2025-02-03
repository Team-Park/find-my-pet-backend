package com.park.animal.post.repository

import com.park.animal.post.entity.Review
import org.springframework.data.mongodb.repository.MongoRepository

interface ReviewRepository : MongoRepository<Review, String>
