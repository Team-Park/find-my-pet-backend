package com.park.animal.user

import org.woo.auth.grpc.AuthProto

data class UserInfoResponse(
    val role: String,
    val email: String,
    val name: String?,
) {
    companion object {
        fun fromProto(userInfoResponse: AuthProto.UserInfoResponse) =
            UserInfoResponse(
                role = userInfoResponse.role,
                email = userInfoResponse.email,
                name = userInfoResponse.name,
            )
    }
}
