@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.park.animal.post.repository

import com.park.animal.common.constants.OrderBy
import com.park.animal.post.dto.Coordinate
import com.park.animal.post.dto.PostDetailResponse
import com.park.animal.post.dto.PostImageResponse
import com.park.animal.post.dto.PostSummaryResponse
import com.park.animal.post.dto.SummarizedPostsByPageDto
import com.park.animal.post.entity.Post
import com.park.animal.post.entity.QPost.post
import com.park.animal.post.entity.QPostImage.postImage
import com.querydsl.core.types.Order
import com.querydsl.core.types.OrderSpecifier
import com.querydsl.core.types.Projections
import com.querydsl.core.types.dsl.CaseBuilder
import com.querydsl.core.types.dsl.ComparablePath
import com.querydsl.core.types.dsl.Expressions
import com.querydsl.jpa.JPAExpressions
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface PostRepository :
    JpaRepository<Post, UUID>,
    PostQueryRepository {
    @Modifying
    @Query("UPDATE Post p SET p.authorName = :newName WHERE p.authorId = :authorId")
    fun updateAuthorName(
        @Param("authorId") authorId: UUID,
        @Param("newName") newName: String,
    )
}

interface PostQueryRepository {
    fun findPostDetailWithImages(
        postId: UUID,
        userId: UUID?,
    ): PostDetailResponse?

    fun findSummarizedPostsByPage(
        size: Long,
        page: Long,
        orderBy: OrderBy,
    ): SummarizedPostsByPageDto

    fun findSummarizedPostsByUserId(userId: UUID): List<PostSummaryResponse>
}

class PostQueryRepositoryImpl(
    private val jpaQueryFactory: JPAQueryFactory,
) : PostQueryRepository {
    override fun findPostDetailWithImages(
        postId: UUID,
        userId: UUID?,
    ): PostDetailResponse? {
        val postDetail = findPostDetail(postId, userId)
        val postImages = findPostImages(postId)

        return postDetail?.apply {
            this.imageUrls = postImages
        }
    }

    private fun findPostDetail(
        postId: UUID,
        userId: UUID?,
    ): PostDetailResponse? =
        jpaQueryFactory
            .select(
                Projections.constructor(
                    PostDetailResponse::class.java,
                    post.authorName,
                    post.title,
                    post.phoneNum,
                    post.time,
                    post.place,
                    post.gender,
                    post.gratuity,
                    post.description,
                    Projections.constructor(
                        Coordinate::class.java,
                        post.lat,
                        post.lng,
                    ),
                    post.openChatUrl,
                    post.missingAnimalStatus,
                    isMine(userId),
                ),
            ).from(post)
            .where(post.id.eq(postId))
            .fetchOne()

    private fun isMine(userId: UUID?) =
        CaseBuilder()
            .`when`(
                userId?.let {
                    post.authorId.eq(it)
                } ?: Expressions.FALSE,
            ).then(1)
            .otherwise(0)

    private fun findPostImages(postId: UUID): List<PostImageResponse> =
        jpaQueryFactory
            .select(
                Projections.constructor(
                    PostImageResponse::class.java,
                    postImage.id,
                    postImage.imageUrl,
                ),
            ).from(postImage)
            .where(postImage.post.id.eq(postId))
            .fetch()

    override fun findSummarizedPostsByPage(
        size: Long,
        page: Long,
        orderBy: OrderBy,
    ): SummarizedPostsByPageDto {
        val offset = page * size
        val result =
            postSummaryWithThumbnail()
                .where(post.deletedAt.isNull)
                .orderBy(setOrderBy(orderBy))
                .offset(offset)
                .limit(size)
                .fetch()

        val totalCount: Long =
            jpaQueryFactory
                .select(post.count())
                .from(post)
                .where(post.deletedAt.isNull)
                .fetchOne() ?: 0L

        return SummarizedPostsByPageDto(
            hasNextPage = totalCount > (size * (page + 1)),
            totalCount = totalCount,
            result = result,
        )
    }

    override fun findSummarizedPostsByUserId(userId: UUID): List<PostSummaryResponse> =
        postSummaryWithThumbnail()
            .where(post.authorId.eq(userId))
            .where(post.deletedAt.isNull)
            .fetch()

    private fun postSummaryWithThumbnail() =
        jpaQueryFactory
            .select(
                Projections.constructor(
                    PostSummaryResponse::class.java,
                    post.id,
                    post.authorName,
                    post.title,
                    post.description,
                    post.gratuity,
                    post.place,
                    post.time,
                    postImage.imageUrl,
                    post.missingAnimalStatus,
                ),
            ).from(post)
            .leftJoin(postImage)
            .on(
                postImage.post.id
                    .eq(post.id)
                    .and(postImage.createdAt.eq(getCreatedAtMinValueFromPostImage(post.id)))
                    .and(postImage.deletedAt.isNull),
            )

    private fun getCreatedAtMinValueFromPostImage(postId: ComparablePath<UUID>) =
        JPAExpressions
            .select(postImage.createdAt.min())
            .from(postImage)
            .where(postImage.post.id.eq(postId))

    private fun setOrderBy(orderBy: OrderBy): OrderSpecifier<*> =
        when (orderBy) {
            OrderBy.UPDATED_AT_DESC -> OrderSpecifier(Order.DESC, post.updatedAt)
            OrderBy.CREATED_AT_DESC -> OrderSpecifier(Order.DESC, post.createdAt)
            OrderBy.CREATED_AT_ASC -> OrderSpecifier(Order.ASC, post.createdAt)
        }
}
