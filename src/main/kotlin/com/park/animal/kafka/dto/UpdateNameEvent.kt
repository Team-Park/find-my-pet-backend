package com.park.animal.kafka.dto

import com.park.animal.common.constants.RecordOperation

data class UpdateNameEvent(
    val userId: String,
    val name: String,
    val recordOperation: RecordOperation,
)
