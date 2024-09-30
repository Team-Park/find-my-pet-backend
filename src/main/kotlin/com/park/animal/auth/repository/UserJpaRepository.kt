package com.park.animal.auth.repository

import com.park.animal.auth.entity.QUser.user
import com.park.animal.auth.entity.User
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserJpaRepository : JpaRepository<User, UUID>, UserQueryRepository

interface UserQueryRepository {
    fun findBySocialId(socialId: String): User?
}

class UserQueryRepositoryImpl(
    private val jpaQueryFactory: JPAQueryFactory,
) : UserQueryRepository {
    override fun findBySocialId(socialId: String): User? {
        return jpaQueryFactory.selectFrom(user)
            .where(user.socialProvider.socialId.eq(socialId))
            .fetchOne()
    }
}
