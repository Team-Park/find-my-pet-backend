package com.park.animal.auth.external

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import org.woo.grpc.AuthMetadata

class TokenInitializeInMetadata(
    val accessToken: String,
) : ClientInterceptor {
    override fun <ReqT : Any?, RespT : Any?> interceptCall(
        p0: MethodDescriptor<ReqT, RespT>?,
        p1: CallOptions?,
        p2: Channel,
    ): ClientCall<ReqT, RespT> =
        object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(p2.newCall(p0, p1)) {
            override fun start(
                responseListener: Listener<RespT>?,
                headers: Metadata?,
            ) {
                headers?.put(AuthMetadata.AUTHORIZATION_METADATA_KEY, "Bearer $accessToken")
                    ?: Metadata().apply { put(AuthMetadata.AUTHORIZATION_METADATA_KEY, "Bearer $accessToken") }
                super.start(responseListener, headers)
            }
        }
}
