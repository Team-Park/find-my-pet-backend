package com.park.animal.multimedia

import annotation.PublicEndPoint
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.woo.http.SucceededApiResponseBody

@RestController
@RequestMapping("/api/v1/upload")
class UploadTestController(
    val fileService: FileService,
) {
    @PostMapping("/image", consumes = ["multipart/form-data", "application/json"])
    @Operation(
        summary = "테스트",
    )
    @PublicEndPoint
    suspend fun addPostImage(
        @RequestParam image: MultipartFile,
    ): SucceededApiResponseBody<Void> {
        fileService.imageUpload(image, "test")
        return SucceededApiResponseBody(data = null)
    }
}
