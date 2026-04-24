package com.park.animal.multimedia

import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.woo.storagesdk.dto.MinioUploadHeader
import org.woo.storagesdk.dto.MinioUploadSpec
import org.woo.storagesdk.usecase.StorageClient
import java.util.UUID

@Service
class FileService(
    private val client: StorageClient,
) {
    suspend fun imageUpload(
        file: MultipartFile,
        uploadedBy: String,
        applicationId: String,
    ): String {
        val objectKey = "test/${UUID.randomUUID()}"
        val spec =
            MinioUploadSpec(
                header =
                    MinioUploadHeader(
                        objectKey = objectKey,
                        contentType = file.contentType ?: "application/octet-stream",
                        contentLength = file.size.toInt(),
                        uploadedBy = uploadedBy,
                        applicationId = applicationId,
                    ),
                data = file.inputStream,
            )
        val response = client.uploadStreamToMinio(spec)
        return "${response.bucket}/${response.objectKey}"
    }
}
