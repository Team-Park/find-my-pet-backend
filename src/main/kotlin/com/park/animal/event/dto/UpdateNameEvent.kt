package com.park.animal.event.dto

import com.park.animal.common.constants.RecordOperation
import com.park.animal.event.DomainEvent

data class UpdateNameEvent(
    val userId: String,
    val name: String,
    val recordOperation: RecordOperation,
) : DomainEvent
