package com.park.animal.me

import com.park.animal.auth.repository.UserInfoRepository
import com.park.animal.common.http.error.ErrorCode
import com.park.animal.common.http.error.exception.BusinessException
import com.park.animal.post.dto.PostSummaryResponse
import com.park.animal.post.repository.PostRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserService(
    private val postRepository: PostRepository,
    private val userInfoRepository: UserInfoRepository,
) {
    @Transactional(readOnly = true)
    fun myPage(userId: UUID): List<PostSummaryResponse> {
        val userInfo = userInfoRepository.findUserInfoByUserId(userId)
            ?: throw BusinessException(ErrorCode.NOT_FOUND_USER)
        return postRepository.findByAuthor(userId).map { post ->
            PostSummaryResponse(
                author = userInfo.name,
                time = post.time,
                title = post.title,
                gratuity = post.gratuity,
                place = post.place,
                thumbnail = null,
            )
        }
    }
}
