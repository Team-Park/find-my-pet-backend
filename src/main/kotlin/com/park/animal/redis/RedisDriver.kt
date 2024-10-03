package com.park.animal.redis

import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class RedisDriver(
    private val redisTemplate: RedisTemplate<String, Any>,
) {
    fun <T> setValue(key: String, value: T, ttl: Long) {
        redisTemplate.opsForValue().set(key, value!!, ttl, TimeUnit.SECONDS)
    }

    fun <T> getValue(key: String, clazz: Class<T>): T? {
        val value = redisTemplate.opsForValue().get(key)
        return clazz.cast(value)
    }
}
