package com.park.animal.common.config

import com.park.animal.common.filter.TraceIdInitializationFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class FilterConfig {
    @Bean
    fun requestResponseFilter(): FilterRegistrationBean<TraceIdInitializationFilter> = FilterRegistrationBean<TraceIdInitializationFilter>()
        .apply {
            this.filter = TraceIdInitializationFilter()
            this.order = Int.MIN_VALUE
        }
}