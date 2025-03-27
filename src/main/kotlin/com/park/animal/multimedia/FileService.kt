package com.park.animal.multimedia

import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.woo.storagesdk.UploadClient

@Service
class FileService(
    private val client: UploadClient,
) {
    suspend fun imageUpload(
        file: MultipartFile,
        uploadedBy: String,
        applicationId: String,
    ) {
        val fileId =
            client.uploadStream(
                fileOriginName = file.originalFilename!!,
                uploadedBy = uploadedBy,
                contentLength = file.size,
                chunkSize = 2000_000,
                applicationId = applicationId,
                data = file.inputStream,
            )
    }
}
