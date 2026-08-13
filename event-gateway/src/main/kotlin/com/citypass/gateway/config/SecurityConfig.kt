package com.citypass.gateway.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${gateway.auth-service-url}") private val authServiceUrl: String,
    @Value("\${gateway.cors-origin}") private val corsOrigin: String,
    @Value("\${gateway.token-audience}") private val audience: String,
    @Value("\${springdoc.swagger-ui.enabled:false}") private val openapiEnabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Cadena aparte para los endpoints de actuator.
     *
     * No exige token, y eso es deliberado: el aislamiento lo da la red, no un header. Las
     * métricas viven en el puerto 9090, que el compose publica sólo en 127.0.0.1 y que el
     * reverse-proxy no rutea, así que desde internet no existen — se leen por túnel SSH,
     * igual que kafka-ui.
     *
     * Pedirles Bearer obligaría a que Prometheus supiera pedir tokens al servicio de
     * identidad, que sólo emite client_credentials para los grupos; sería una credencial
     * más para administrar a cambio de nada.
     */
    @Bean
    @Order(1)
    fun actuatorFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher(EndpointRequest.toAnyEndpoint())
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .csrf { it.disable() }
        return http.build()
    }

    @Bean
    @Order(2)
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        logger.info("Seguridad JWT activada — JWKS: $authServiceUrl/.well-known/jwks.json")
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    // `/health` lo consulta el orquestador, que no tiene con qué
                    // autenticarse, y no expone ningún dato.
                    //
                    // La resolución de schemas por id queda abierta porque la usan los
                    // deserializadores estándar, que la llaman solos al leer un evento y
                    // cuyo soporte de Bearer varía según la librería. Es de sólo lectura
                    // y devuelve contratos, no datos.
                    //
                    .requestMatchers("/health").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/schemas/**").permitAll()

                    // La documentación OpenAPI se abre sólo cuando está habilitada, que
                    // es únicamente en desarrollo (OPENAPI_ENABLED en el .env). No sirve
                    // exigirle Bearer: el navegador no manda el header al cargar la
                    // página, así que protegida es lo mismo que apagada, pero da un 401
                    // que parece un bug. En producción springdoc no publica las rutas y
                    // además esta regla no aplica, así que hacen falta las dos cosas
                    // para que exista.
                    .apply {
                        if (openapiEnabled) {
                            requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        }
                    }

                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt -> jwt.decoder(jwtDecoder()) }
            }

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration()
        config.allowedOrigins = corsOrigin.split(",")
        config.allowedMethods = listOf("GET", "POST", "DELETE", "OPTIONS")
        config.allowedHeaders = listOf("Content-Type", "Authorization")
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }

    /**
     * Valida firma, vencimiento **y audiencia**.
     *
     * Sin el chequeo de audiencia, un token emitido por el mismo servicio de identidad
     * para otro destinatario sería aceptado acá. El `aud` es lo que dice para quién fue
     * emitido, y verificarlo evita que un token de otro sistema sirva contra el gateway.
     */
    @Bean
    fun jwtDecoder(): JwtDecoder =
        NimbusJwtDecoder.withJwkSetUri("$authServiceUrl/.well-known/jwks.json").build().apply {
            setJwtValidator(
                DelegatingOAuth2TokenValidator(
                    JwtValidators.createDefault(),
                    JwtClaimValidator<List<String>>(JwtClaimNames.AUD) { it != null && audience in it }
                )
            )
        }
}
