package com.park.animal.multimedia

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.woo.storagesdk.usecase.StorageClient
import org.woo.storagesdk.usecase.StorageClient.Companion.PUBLIC_ACCESS_LEVEL

@Service
class MultimediaService(
    private val client: StorageClient,
    @Value("\${platform-holder.cdn.domain}")
    private val myCdnUrl: String,
) {
    companion object {
        const val UPLOAD_CHUNK_SIZE = 2000_000
        const val CDN_PATH = "/api/v1/download"
    }

    private suspend fun imageUpload(
        file: MultipartFile,
        uploadedBy: String,
        applicationId: String,
    ): Long =
        client.uploadStream(
            fileOriginName = file.originalFilename!!,
            uploadedBy = uploadedBy,
            contentLength = file.size,
            chunkSize = UPLOAD_CHUNK_SIZE,
            applicationId = applicationId,
            data = file.inputStream,
            accessLevel = PUBLIC_ACCESS_LEVEL,
        )

    private fun generateImageUrl(fileId: Long): String = "$myCdnUrl$CDN_PATH/$fileId"

    suspend fun uploadMultipartFiles(
        files: List<MultipartFile>,
        userId: String,
        applicationId: String,
    ): List<String>? =
        coroutineScope {
            files
                .map { file ->
                    async {
                        val fileId = imageUpload(file, userId, applicationId)
                        generateImageUrl(fileId)
                    }
                }.awaitAll()
        }
}
