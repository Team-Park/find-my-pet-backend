package com.park.animal.common.config

import com.park.animal.common.filter.MDCInitializationFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class FilterConfig {
    @Bean
    fun requestResponseFilter(): FilterRegistrationBean<MDCInitializationFilter> = FilterRegistrationBean<MDCInitializationFilter>()
        .apply {
            this.filter = MDCInitializationFilter()
            this.order = Int.MIN_VALUE
        }
}