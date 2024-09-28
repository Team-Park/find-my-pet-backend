package com.park.animal.post

import com.park.animal.common.annotation.PublicEndPoint
import com.park.animal.post.dto.RegistrationPostRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1")
class PostController(
    private val postService: PostService,
) {
    @GetMapping("/posts")
    fun getPosts() {
    }

    @PostMapping("/post")
    @PublicEndPoint
    suspend fun registerPost(
        @RequestPart("body") request: RegistrationPostRequest,
        @RequestPart("image") image: MultipartFile,
    ) {
        postService
    }
}
