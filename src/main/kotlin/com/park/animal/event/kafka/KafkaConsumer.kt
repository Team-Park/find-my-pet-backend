package com.park.animal.event.kafka

import com.park.animal.event.dto.UpdateNameEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.JacksonUtils
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.util.concurrent.Executors

@Component
class KafkaConsumer {
    private val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(executor)

    @KafkaListener(topics = ["\${topic.name-update}"], groupId = "\${spring.kafka.consumer.group-id}")
    fun listen(
        @Payload payload: String,
    ) {
        scope.launch {
            val json = JacksonUtils.enhancedObjectMapper().readValue(payload, UpdateNameEvent::class.java)
        }
    }
}
