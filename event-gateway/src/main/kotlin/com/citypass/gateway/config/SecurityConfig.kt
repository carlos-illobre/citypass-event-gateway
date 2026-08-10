package com.citypass.gateway.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${proxy.security.enabled:false}") private val securityEnabled: Boolean,
    @Value("\${proxy.auth-service-url:http://localhost:8083}") private val authServiceUrl: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        // Endpoints que no requieren autenticación
        val PUBLIC_PATHS = arrayOf(
            "/api/v1/health",
            "/api/v1/schemas",
            "/api/v1/schemas/**",
            "/api/v1/dlq",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**"
        )
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }

        if (securityEnabled) {
            logger.info("Seguridad JWT activada — JWKS: $authServiceUrl/.well-known/jwks.json")
            http.authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(*PUBLIC_PATHS).permitAll()
                    .anyRequest().authenticated()
            }
            http.oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt -> jwt.decoder(jwtDecoder()) }
            }
        } else {
            logger.warn("Seguridad JWT DESACTIVADA (SECURITY_ENABLED=false) — todos los endpoints son públicos")
            http.authorizeHttpRequests { it.anyRequest().permitAll() }
        }

        return http.build()
    }

    @Bean
    fun jwtDecoder(): JwtDecoder =
        if (securityEnabled)
            NimbusJwtDecoder.withJwkSetUri("$authServiceUrl/.well-known/jwks.json").build()
        else
            JwtDecoder { _ -> throw UnsupportedOperationException("Security disabled") }
}
