package com.park.animal.auth.service

import com.park.animal.auth.Role
import com.park.animal.auth.SocialLoginProvider
import com.park.animal.auth.SocialProvider
import com.park.animal.auth.User
import com.park.animal.auth.repository.UserJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserJpaRepository,
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
}
