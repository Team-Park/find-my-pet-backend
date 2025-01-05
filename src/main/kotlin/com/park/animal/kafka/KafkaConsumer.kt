package com.park.animal.kafka

import com.park.animal.common.constants.RecordOperation
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.JacksonUtils
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component

@Component
class KafkaConsumer {
    @KafkaListener(topics = ["\${topic.name-update}"], groupId = "\${spring.kafka.consumer.group-id}")
    fun listen(
        @Payload payload: String,
    ) {
        val json = JacksonUtils.enhancedObjectMapper().readValue(payload, UserName::class.java)
        println(json)
    }
}

data class UserName(
    val userId: String,
    val name: String,
    val recordOperation: RecordOperation,
)
