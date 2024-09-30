package com.park.animal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AnimalApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<AnimalApplication>(*args)
}
