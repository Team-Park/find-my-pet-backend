package com.park.animal.common.config

import com.example.grpc.filedelete.FileDeleteServiceGrpcKt
import com.example.grpc.fileupload.FileUploadServiceGrpcKt
import com.example.grpc.fileupload.StorageServiceGrpcKt
import com.example.grpc.minioadmin.MinioAdminServiceGrpcKt
import kotlinx.coroutines.asCoroutineDispatcher
import net.devh.boot.grpc.client.inject.GrpcClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.woo.grpc.circuitbreaker.GrpcCircuitBreaker
import org.woo.storagesdk.interceptor.AllowExtension.IMAGE
import org.woo.storagesdk.interceptor.FileExtensionInterceptor
import org.woo.storagesdk.usecase.DeleteClient
import org.woo.storagesdk.usecase.DeleteService
import org.woo.storagesdk.usecase.MinioAdminClient
import org.woo.storagesdk.usecase.MinioAdminService
import org.woo.storagesdk.usecase.StorageClient
import org.woo.storagesdk.usecase.StorageService

@Configuration
class StorageConfig {
    @GrpcClient("storage")
    private lateinit var cassandraClient: FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub

    @GrpcClient("storage")
    private lateinit var minioClient: StorageServiceGrpcKt.StorageServiceCoroutineStub

    @GrpcClient("storage")
    private lateinit var deleteStub: FileDeleteServiceGrpcKt.FileDeleteServiceCoroutineStub

    @GrpcClient("storage")
    private lateinit var minioAdminStub: MinioAdminServiceGrpcKt.MinioAdminServiceCoroutineStub

    // find-my-pet 은 게시글 이미지만 취급 — IMAGE 화이트리스트만 허용
    private val fileExtensionInterceptor = FileExtensionInterceptor(setOf(IMAGE))

    @Bean
    fun uploadClient(): StorageClient =
        StorageService(
            uploadStubToCassandra = cassandraClient,
            uploadStubToMinio = minioClient,
            uploadInterceptors = listOf(fileExtensionInterceptor),
            circuitBreaker = GrpcCircuitBreaker(),
        )

    @Bean
    fun deleteClient(
        @Qualifier("grpcThreadPool")
        executor: ThreadPoolTaskExecutor,
    ): DeleteClient =
        DeleteService(
            stub = deleteStub,
            circuitBreaker = GrpcCircuitBreaker(),
            dispatcher = executor.asCoroutineDispatcher(),
        )

    @Bean
    fun minioAdminClient(
        @Qualifier("grpcThreadPool")
        executor: ThreadPoolTaskExecutor,
    ): MinioAdminClient =
        MinioAdminService(
            stub = minioAdminStub,
            circuitBreaker = GrpcCircuitBreaker(),
            dispatcher = executor.asCoroutineDispatcher(),
        )
}
