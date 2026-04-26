package com.park.animal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.client.discovery.EnableDiscoveryClient
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
class AnimalApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<AnimalApplication>(*args)
}
