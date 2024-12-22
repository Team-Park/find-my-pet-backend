package com.park.animal.auth.external

import com.google.protobuf.Empty
import kotlinx.coroutines.suspendCancellableCoroutine
import net.devh.boot.grpc.client.inject.GrpcClient
import org.springframework.stereotype.Service
import org.woo.auth.grpc.AuthProto
import org.woo.auth.grpc.UserInfoServiceGrpc
import org.woo.log.log

@Service
class AuthGrpcService {
    @GrpcClient("auth")
    lateinit var userInfoServiceStub: UserInfoServiceGrpc.UserInfoServiceBlockingStub

    suspend fun getUserInfo(token: String): AuthProto.UserInfoResponse =
        suspendCancellableCoroutine {
            // 요청 객체 생성
            runCatching {
                val response =
                    userInfoServiceStub
                        .withInterceptors(TokenInitializeInMetadata(token))
                        .getUserInfoByBearer(
                            Empty.newBuilder().build(),
                        )
                log().info("grpc response = ${response.id}")
                response
            }.onFailure {
                log().error(it.stackTraceToString())
            }
        }
}
