package com.park.animal.post.dto

data class SummarizedPostsByPageDto(
    val hasNextPage: Boolean,
    val totalCount: Long,
    val result: List<PostSummaryResponse>,
)
