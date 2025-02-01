package com.park.animal.common.config

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class FlywayConfig {
    @Bean
    fun cleanMigrateStrategy(): FlywayMigrationStrategy =
        FlywayMigrationStrategy { flyway ->
            flyway.repair()
            flyway.migrate()
        }
}
