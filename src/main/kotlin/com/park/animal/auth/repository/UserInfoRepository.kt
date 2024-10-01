package com.park.animal.auth.repository

import com.park.animal.auth.entity.QUser.user
import com.park.animal.auth.entity.QUserInfo.userInfo
import com.park.animal.auth.entity.UserInfo
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserInfoRepository : JpaRepository<UserInfo, UUID>, UserInfoQueryRepository

interface UserInfoQueryRepository {
    fun findUserInfoByUserId(userId: UUID): UserInfo?
}

class UserInfoQueryRepositoryImpl(
    private val jpaQueryFactory: JPAQueryFactory,
) : UserInfoQueryRepository {
    override fun findUserInfoByUserId(userId: UUID): UserInfo? {
        return jpaQueryFactory.selectFrom(userInfo)
            .join(userInfo.user, user).fetchJoin()
            .where(user.id.eq(userId))
            .fetchOne()
    }
}
