package com.park.animal.post.dto

import com.park.animal.common.constants.OrderBy

data class SummarizedPostsByPageQuery(
    val size: Long,
    val offset: Long,
    val orderBy: OrderBy,
)
