package com.park.animal.publicdata.dto

data class AbandonedAnimalPage(
    val contents: List<AbandonedAnimalResponse>,
    val hasNextPage: Boolean,
    val totalCount: Long,
)
