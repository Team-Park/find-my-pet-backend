package com.park.animal.auth.service

import com.park.animal.auth.SocialLoginProvider
import com.park.animal.auth.entity.Role
import com.park.animal.auth.entity.SocialProvider
import com.park.animal.auth.entity.User
import com.park.animal.auth.entity.UserInfo
import com.park.animal.auth.repository.UserInfoRepository
import com.park.animal.auth.repository.UserJpaRepository
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.post.dto.PostSummaryResponse
import com.park.animal.post.repository.PostRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class UserService(
    private val userRepository: UserJpaRepository,
    private val userInfoRepository: UserInfoRepository,
    private val postRepository: PostRepository,
) {
    @Transactional(readOnly = true)
    fun findBySocialId(socialId: String): User? = userRepository.findBySocialId(socialId)

    fun saveUser(provider: SocialLoginProvider, socialId: String): User {
        val user = User(
            email = null,
            password = null,
            socialProvider = SocialProvider(
                provider = provider,
                socialId = socialId,
            ),
            role = Role.ROLE_USER,
            profile = null,
        )
        return userRepository.save(user)
    }

    @Transactional
    fun saveUserInfo(user: User, name: String) {
        val userInfo = UserInfo(
            user = user,
            name = name,
        )
        userInfoRepository.save(userInfo)
    }

    @Transactional(readOnly = true)
    fun myPage(userId: UUID): List<PostSummaryResponse> {
        return postRepository.findSummarizedPostsByUserId(userId)
    }

    @Transactional(readOnly = true)
    fun findById(userId: UUID): User {
        return userRepository.findById(userId).orElseThrow {
            throw BusinessException(ErrorCode.NOT_FOUND_USER)
        }
    }
}
