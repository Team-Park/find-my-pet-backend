package com.park.animal.common.config

import com.example.grpc.fileupload.FileUploadServiceGrpcKt
import net.devh.boot.grpc.client.inject.GrpcClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.woo.storagesdk.UploadClient
import org.woo.storagesdk.UploadService

@Configuration
class StorageConfig {
    @GrpcClient("storage")
    private lateinit var storageClient: FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub

    @Bean
    fun client(): UploadClient = UploadService(storageClient)
}
