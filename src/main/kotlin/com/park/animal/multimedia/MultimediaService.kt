package com.park.animal.multimedia

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.woo.storagesdk.dto.MinioUploadHeader
import org.woo.storagesdk.dto.MinioUploadSpec
import org.woo.storagesdk.usecase.StorageClient
import java.util.UUID

/**
 * 게시글 이미지 업로드 · 조회용 Presigned URL 래퍼.
 *
 * 저장 모델: `post_image.image_url` 에는 `{bucket}/{objectKey}` composite key 를 저장한다.
 * 읽을 때 [resolvePresignedUrl] 로 full URL 로 변환. 기존 Cassandra 시대 데이터(`http...`)는 그대로 통과.
 */
@Service
class MultimediaService(
    private val client: StorageClient,
) {
    companion object {
        private const val SERVICE_PATH = "post"
        private const val DEFAULT_EXPIRY_SECONDS = 3600 // 1h
    }

    private fun newObjectKey(): String = "$SERVICE_PATH/${UUID.randomUUID()}"

    /** `bucket/objectKey` composite 로 저장 — 읽을 때 split 해서 presign. */
    private fun composite(bucket: String, objectKey: String): String = "$bucket/$objectKey"

    suspend fun uploadMultipartFiles(
        files: List<MultipartFile>,
        userId: String,
        applicationId: String,
    ): List<String> =
        coroutineScope {
            files
                .map { file ->
                    async {
                        val objectKey = newObjectKey()
                        val header =
                            MinioUploadHeader(
                                objectKey = objectKey,
                                contentType = file.contentType ?: "application/octet-stream",
                                contentLength = file.size.toInt(),
                                uploadedBy = userId,
                                applicationId = applicationId,
                            )
                        val spec = MinioUploadSpec(header = header, data = file.inputStream)
                        val response = client.uploadStreamToMinio(spec)
                        composite(response.bucket, response.objectKey)
                    }
                }.awaitAll()
        }

    /**
     * 저장된 image_url(composite or legacy HTTP) 를 클라이언트가 바로 렌더 가능한 URL 로 변환.
     * - `http://` / `https://` 로 시작하면 기존 Cassandra CDN URL 로 그대로 반환 (legacy).
     * - 아니면 `bucket/objectKey` 로 보고 Presigned URL 발급.
     */
    suspend fun resolvePresignedUrl(stored: String): String {
        if (stored.startsWith("http://") || stored.startsWith("https://")) return stored
        val slash = stored.indexOf('/')
        if (slash <= 0) return stored
        val bucket = stored.substring(0, slash)
        val objectKey = stored.substring(slash + 1)
        return client.getDownloadPresignedUrl(
            bucket = bucket,
            objectKey = objectKey,
            expirySeconds = DEFAULT_EXPIRY_SECONDS,
        )
    }

    suspend fun resolvePresignedUrls(stored: List<String>): List<String> =
        coroutineScope {
            stored
                .map { async { resolvePresignedUrl(it) } }
                .awaitAll()
        }
}
