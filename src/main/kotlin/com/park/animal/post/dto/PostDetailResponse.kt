package com.park.animal.post.dto

import java.time.LocalDateTime

data class PostDetailResponse(
    val author: String,
    val title: String,
    val phoneNum: String,
    val time: LocalDateTime,
    val place: String,
    val gender: String,
    val gratuity: Int,
    val description: String,
    val coordinate: Coordinate,
    val openChatUrl: String?,
    var isMine: Boolean,
) {
    lateinit var imageUrls: List<PostImageResponse>

    constructor(
        name: String,
        title: String,
        phoneNum: String,
        time: LocalDateTime,
        place: String,
        gender: String,
        gratuity: Int,
        description: String,
        coordinate: Coordinate,
        openChatUrl: String?,
        isMineInt: Int,
    ) : this(
        name,
        title,
        phoneNum,
        time,
        place,
        gender,
        gratuity,
        description,
        coordinate,
        openChatUrl,
        isMineInt == 1,
    )
}
