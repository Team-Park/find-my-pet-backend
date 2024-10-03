package com.park.animal.common.config

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.PlatformTransactionManager

@Configuration
class RedisConfig(
    @Value("\${spring.data.redis.host}")
    private val host: String,
    @Value("\${spring.data.redis.port}")
    private val port: Int,
    @Value("\${spring.data.redis.password}")
    private val password: String,
    @Value("\${spring.profiles.active}")
    private val active: String,
) {
    companion object {
        const val REDISSON_PREFIX = "redis://"
    }

    @Bean
    fun redisConnectionFactory(): RedisConnectionFactory {
        val config = RedisStandaloneConfiguration(host, port)
        config.setPassword(RedisPassword.of(password))
        return LettuceConnectionFactory(config)
    }

    @Bean
    fun redisTemplate(redisConnectionFactory: RedisConnectionFactory): RedisTemplate<String, Any> {
        val redisTemplate = RedisTemplate<String, Any>()
        redisTemplate.connectionFactory = redisConnectionFactory
        redisTemplate.setEnableTransactionSupport(true)
        val jsonSerializer = GenericJackson2JsonRedisSerializer()
        val stringSerializer = StringRedisSerializer()

        redisTemplate.keySerializer = stringSerializer
        redisTemplate.valueSerializer = jsonSerializer
        redisTemplate.hashKeySerializer = stringSerializer
        redisTemplate.hashValueSerializer = jsonSerializer
        return redisTemplate
    }

    @Bean
    fun redissonClient(): RedissonClient {
        val config = org.redisson.config.Config()
        config.useSingleServer().setAddress("$REDISSON_PREFIX$host:$port")
        if (active != "local") {
            config.useSingleServer().setPassword(password)
        }
        return Redisson.create(config)
    }

    @Bean
    fun transactionManager(): PlatformTransactionManager {
        return JpaTransactionManager()
    }
}
