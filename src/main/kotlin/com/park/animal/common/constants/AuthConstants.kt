package com.park.animal.common.constants

object AuthConstants {
    const val USER_ID = "user_id"
    const val USER_ROLE = "user_role"

    const val AUTHORIZATION_HEADER = "Authorization"
    const val BEARER_PREFIX = "Bearer " // 1 blank

    // redis
    const val REFRESH_TOKEN_PREFIX = "token:"
}
