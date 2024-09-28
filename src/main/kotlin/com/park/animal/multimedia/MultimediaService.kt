package com.park.animal.multimedia

import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

@Service
class MultimediaService(
    private val s3Adapter: S3Adapter,
)  {
    suspend fun uploadMultipartFiles(files: List<MultipartFile>): List<String>? {
        return s3Adapter.uploadFiles(files)
    }

    suspend fun uploadMultipartFile(file: MultipartFile): String? {
        val command = listOf(file)
        return s3Adapter.uploadFiles(command)?.get(0)
    }
}
