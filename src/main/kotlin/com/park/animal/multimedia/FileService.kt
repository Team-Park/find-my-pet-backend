package com.park.animal.multimedia

import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.woo.storagesdk.usecase.StorageClient
import org.woo.storagesdk.usecase.StorageClient.Companion.PUBLIC_ACCESS_LEVEL

@Service
class FileService(
    private val client: StorageClient,
) {
    suspend fun imageUpload(
        file: MultipartFile,
        uploadedBy: String,
        applicationId: String,
    ) {
        client.uploadStream(
            fileOriginName = file.originalFilename!!,
            uploadedBy = uploadedBy,
            contentLength = file.size,
            chunkSize = 2000_000,
            applicationId = applicationId,
            data = file.inputStream,
            accessLevel = PUBLIC_ACCESS_LEVEL,
        )
    }
}
