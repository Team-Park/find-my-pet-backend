package com.park.animal.post.entity

import io.swagger.v3.oas.annotations.media.Schema

enum class MissingAnimalStatus {
    @Schema(description = "유실동물 찾는중")
    SEARCHING,

    @Schema(description = "유실동물 찾음")
    FOUND,
}
