package com.park.animal.common.config

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

@Configuration
@EnableJpaAuditing
class JpaConfig(
    @PersistenceContext
    private val em: EntityManager? = null,
) {
    @Bean
    fun jpaQueryFactory(): JPAQueryFactory = JPAQueryFactory(em)
}
