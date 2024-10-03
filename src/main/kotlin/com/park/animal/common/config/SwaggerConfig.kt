package com.park.animal.common.config

import com.park.animal.common.config.SwaggerConfig.Companion.AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.servers.Server
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@OpenAPIDefinition(
    servers = [Server(url = "/")],
)
open class SwaggerConfig {
    @Bean
    open fun openAPI(): OpenAPI = OpenAPI()
        .components(
            Components().addSecuritySchemes(
                AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME,
                AuthorizationBearerSecurityScheme,
            ),
        )
        .info(info())

    private fun info() = Info().title("find-my-pet-api-doc")
        .description(
            """
            Find My Pet 명세서
            """.trimIndent(),
        )
        .version("1")

    companion object {
        const val AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME = "Authorization: Bearer ACCESS_TOKEN"
    }
}

val AuthorizationBearerSecurityScheme: SecurityScheme = SecurityScheme()
    .name(AUTHORIZATION_BEARER_SECURITY_SCHEME_NAME)
    .type(SecurityScheme.Type.HTTP)
    .scheme("Bearer")
    .bearerFormat("JWT")
