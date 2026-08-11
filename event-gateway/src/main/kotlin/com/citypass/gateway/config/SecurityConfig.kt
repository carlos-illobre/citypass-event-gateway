package com.citypass.gateway.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain

/**
 * Configuración de seguridad HTTP con JWT (RS256 + JWKS).
 *
 * Cuando [securityEnabled] es true, los endpoints de escritura requieren un token JWT válido
 * firmado con RS256. El gateway valida la firma contra el JWKS endpoint del servicio de
 * autenticación. Los endpoints públicos (health, schemas, DLQ, Swagger) no requieren token.
 *
 * Cuando [securityEnabled] es false, todos los endpoints son accesibles sin autenticación.
 *
 * @param securityEnabled Activa o desactiva la validación JWT (variable de entorno SECURITY_ENABLED).
 * @param authServiceUrl URL base del servicio de autenticación que expone el JWKS (variable de entorno AUTH_SERVICE_URL).
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${gateway.security.enabled}") private val securityEnabled: Boolean,
    @Value("\${gateway.auth-service-url}") private val authServiceUrl: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val openPaths = arrayOf(
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/v3/api-docs/**"
    )

    /**
     * Configura la cadena de filtros de seguridad HTTP.
     *
     * Desactiva CSRF (API stateless), configura sesiones stateless,
     * y aplica autenticación JWT si está habilitada.
     *
     * @param http Builder de seguridad HTTP de Spring.
     * @return Cadena de filtros configurada.
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }

        if (securityEnabled) {
            logger.info("Seguridad JWT activada — JWKS: $authServiceUrl/.well-known/jwks.json")
            http.authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(HttpMethod.GET, "/api/v1/health", "/api/v1/schemas", "/api/v1/schemas/**", "/api/v1/dlq").permitAll()
                    .requestMatchers(*openPaths).permitAll()
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

    /**
     * Crea el decoder JWT que valida firmas RS256 contra el JWKS del servicio de autenticación.
     *
     * Cuando la seguridad está desactivada, retorna un decoder placeholder que lanza excepción
     * si es invocado — esto satisface la inyección de dependencias de Spring sin exponer un
     * decoder funcional.
     *
     * @return Decoder JWT configurado con el JWKS URI, o placeholder si seguridad desactivada.
     */
    @Bean
    fun jwtDecoder(): JwtDecoder =
        if (securityEnabled)
            NimbusJwtDecoder.withJwkSetUri("$authServiceUrl/.well-known/jwks.json").build()
        else
            JwtDecoder { throw UnsupportedOperationException("JWT no configurado — SECURITY_ENABLED=false") }
}
