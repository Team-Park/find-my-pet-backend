package com.park.animal.common.config

import com.example.grpc.fileupload.FileUploadServiceGrpcKt
import net.devh.boot.grpc.client.inject.GrpcClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.woo.grpc.circuitbreaker.GrpcCircuitBreaker
import org.woo.storagesdk.interceptor.AllowExtension.IMAGE
import org.woo.storagesdk.interceptor.FileExtensionInterceptor
import org.woo.storagesdk.usecase.StorageClient
import org.woo.storagesdk.usecase.StorageService

@Configuration
class StorageConfig {
    @GrpcClient("storage")
    private lateinit var storageClient: FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub

    @Bean
    fun client(): StorageClient =
        StorageService(
            uploadStubToCassandra = storageClient,
            uploadInterceptors = listOf(FileExtensionInterceptor(setOf(IMAGE))),
            circuitBreaker = GrpcCircuitBreaker(),
        )
}
