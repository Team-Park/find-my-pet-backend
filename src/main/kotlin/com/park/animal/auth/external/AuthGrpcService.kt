package com.park.animal.auth.external

import com.google.protobuf.Empty
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import net.devh.boot.grpc.client.inject.GrpcClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.woo.auth.grpc.AuthProto
import org.woo.auth.grpc.UserInfoServiceGrpc
import org.woo.log.log

@Service
class AuthGrpcService(
    @Value("\${auth.host}")
    val authHost: String,
) {
    @GrpcClient("auth")
    lateinit var userInfoServiceStub: UserInfoServiceGrpc.UserInfoServiceBlockingStub

    init {
        val channel =
            NettyChannelBuilder
                .forAddress(authHost, 9090)
                .usePlaintext()
                .build()
        userInfoServiceStub = UserInfoServiceGrpc.newBlockingStub(channel)
    }

    suspend fun getUserInfo(token: String): AuthProto.UserInfoResponse =
        suspendCancellableCoroutine {
            // 요청 객체 생성
            runCatching {
                userInfoServiceStub
                    .withInterceptors(TokenInitializeInMetadata(token))
                    .getUserInfoByBearer(
                        Empty.newBuilder().build(),
                    )
            }.onFailure {
                log().error(it.stackTraceToString())
            }
        }
}
