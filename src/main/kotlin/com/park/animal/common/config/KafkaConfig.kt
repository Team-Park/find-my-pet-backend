package com.park.animal.common.config

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

@Configuration
@EnableKafka
class KafkaConfig(
    //    리소스를 최소화 하기위해 도메인을 통합하여 Kafka 를 사용한다.
    @Value("\${consume.kafka.bootstrap.server}")
    val boostrapServers: String,
    @Value("\${spring.kafka.consumer.group-id}")
    val groupId: String,
) {
    @Bean
    fun consumerFactory(): ConsumerFactory<String, String> {
        val props = generateConsumerConfig()
        return DefaultKafkaConsumerFactory(props)
    }

    private fun generateConsumerConfig(): Map<String, Any> {
        val props: MutableMap<String, Any> = mutableMapOf()
        props[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = boostrapServers
        props[ConsumerConfig.GROUP_ID_CONFIG] = groupId
        props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        return props
    }

    private fun generateProducerConfig(): Map<String, Any> {
        val props: MutableMap<String, Any> = mutableMapOf()
        props[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = boostrapServers
        props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        return props
    }

    @Bean
    fun producerFactory(): ProducerFactory<String, Any> {
        val props = generateProducerConfig()
        return DefaultKafkaProducerFactory(props)
    }

    @Bean
    fun kafkaTemplate(): KafkaTemplate<String, Any> = KafkaTemplate(producerFactory())
}
