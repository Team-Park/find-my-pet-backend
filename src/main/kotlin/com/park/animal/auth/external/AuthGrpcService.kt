package com.park.animal.auth.external

import com.google.protobuf.Empty
import kotlinx.coroutines.suspendCancellableCoroutine
import net.devh.boot.grpc.client.inject.GrpcClient
import org.springframework.stereotype.Service
import org.woo.apm.log.log
import org.woo.auth.grpc.AuthProto
import org.woo.auth.grpc.UserInfoServiceGrpc
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Service
class AuthGrpcService {
    @GrpcClient("auth")
    lateinit var userInfoServiceStub: UserInfoServiceGrpc.UserInfoServiceBlockingStub

    suspend fun getUserInfo(token: String): AuthProto.UserInfoResponse =
        suspendCancellableCoroutine { continuation ->
            // 요청 객체 생성
            runCatching {
                val response =
                    userInfoServiceStub
                        .withInterceptors(TokenInitializeInMetadata(token))
                        .getUserInfoByBearer(
                            Empty.newBuilder().build(),
                        )
                response
            }.onFailure { exception ->
                log().error(exception.stackTraceToString())
                continuation.resumeWithException(exception)
            }.onSuccess { response ->
                log().info("grpc response = ${response.id}")
                continuation.resume(response)
            }
        }
}
