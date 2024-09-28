package com.park.animal.post.repository

import com.park.animal.auth.entity.QUser.user
import com.park.animal.auth.entity.QUserInfo.userInfo
import com.park.animal.post.dto.PostDetailResponse
import com.park.animal.post.entity.Post
import com.park.animal.post.entity.QPost
import com.park.animal.post.entity.QPost.post
import com.park.animal.post.entity.QPostImage
import com.park.animal.post.entity.QPostImage.postImage
import com.querydsl.core.types.Projections
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime
import java.util.UUID

interface PostRepository : JpaRepository<Post, UUID>, PostQueryRepository

interface PostQueryRepository {
    fun findPostDetail(postId: UUID): PostDetailResponse?
}

class PostQueryRepositoryImpl(
    private val jpaQueryFactory: JPAQueryFactory,
) : PostQueryRepository {
    override fun findPostDetail(postId: UUID): PostDetailResponse? {
//        val author: String,
//        val imageUrls: List<String>,
//        val title: String,
//        val phoneNum: String,
//        val time: LocalDateTime,
//        val place: String,
//        val gender: String,
//        val gratuity: Int,
//        val description: String,
        return jpaQueryFactory.select(
            Projections.constructor(
                PostDetailResponse::class.java,
                userInfo.name,
                postImage.imageUrl,
                post.title,
                userInfo.name,
            )
        )
            .from(post)
            .leftJoin(postImage.post, post).on(postImage.post.id.eq(post.id)).fetchJoin()
            .leftJoin(userInfo).on(userInfo.user.eq(user)).fetchJoin()  // UserInfo를 User와 fetch join
            .where(post.id.eq(postId))
            .fetchOne()
    }
}
