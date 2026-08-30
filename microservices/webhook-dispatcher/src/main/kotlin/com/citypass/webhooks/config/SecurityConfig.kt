package com.citypass.webhooks.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Valida los tokens de quien administra suscripciones y consulta la cola de fallidos.
 *
 * Es la misma validación que hace el gateway, y está duplicada a propósito: confiar en un
 * encabezado puesto por otro servicio sólo sería seguro con un aislamiento de red que hoy
 * no existe. Un servicio que decide sobre datos de un equipo tiene que verificar por sí
 * mismo de quién son.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${dispatcher.auth-service-url}") private val authServiceUrl: String,
    @Value("\${dispatcher.cors-origin}") private val corsOrigin: String,
    @Value("\${dispatcher.token-audience}") private val audience: String,
    @Value("\${dispatcher.token-issuer:}") private val issuer: String,
    @Value("\${dispatcher.token-contract-version:1}") private val contractVersion: Int,
    @Value("\${springdoc.swagger-ui.enabled:false}") private val openapiEnabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    // Lo consulta el orquestador, que no tiene con qué autenticarse, y no
                    // expone nada de nadie.
                    .requestMatchers("/health").permitAll()
                    // Canal interno del gateway. Su aislamiento es la red de Docker: el
                    // proxy no rutea `/internal/`, así que desde afuera no existe.
                    //
                    // Pedirle un token obligaría al gateway a administrar una credencial
                    // de servicio para hablar con un vecino de la misma red, y esa
                    // credencial —a diferencia de la regla del proxy— sí puede filtrarse.
                    .requestMatchers("/internal/**").permitAll()

                    // La documentación OpenAPI se abre sólo cuando está habilitada, que es
                    // únicamente con el perfil `development`. No sirve exigirle Bearer: el
                    // navegador no manda el header al cargar la página, así que protegida
                    // es lo mismo que apagada pero da un 401 que parece un bug —y eso es
                    // literalmente lo que este servicio devolvía antes de esta regla, con
                    // su propio OpenApiConfig documentando una API que nadie podía leer.
                    //
                    // En producción springdoc no publica las rutas y además esta regla no
                    // aplica, así que hacen falta las dos cosas para que exista.
                    .apply {
                        if (openapiEnabled) {
                            // `/doc` es la ruta configurada; springdoc redirige desde ahí a
                            // los recursos estáticos de swagger-ui, que también hay que abrir.
                            requestMatchers("/doc", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        }
                    }

                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 -> oauth2.jwt { } }
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
     * Firma, vencimiento, emisor, audiencia, tipo de token y versión del contrato.
     *
     * El detalle de por qué cada validador existe está en el
     * `SecurityConfig` del gateway y en `docs/AUTH.md`: acá se aplica el mismo contrato de
     * identidad, sin variantes.
     */
    @Bean
    fun jwtDecoder(): JwtDecoder =
        NimbusJwtDecoder.withJwkSetUri("$authServiceUrl/.well-known/jwks.json").build().apply {
            setJwtValidator(DelegatingOAuth2TokenValidator(validadores()))
        }

    internal fun validadores(): List<OAuth2TokenValidator<Jwt>> = buildList {
        add(JwtValidators.createDefault())
        add(JwtClaimValidator<List<String>>(JwtClaimNames.AUD) { it != null && audience in it })
        add(JwtClaimValidator<String>(TOKEN_USE) { it == SERVICE })
        add(validadorDeVersion())
        if (issuer.isNotBlank()) {
            add(JwtClaimValidator<String>(JwtClaimNames.ISS) { it == issuer })
        } else {
            logger.warn(
                "dispatcher.token-issuer está vacío: no se valida el emisor de los tokens."
            )
        }
    }

    /** Ver el equivalente del gateway: `JwtClaimValidator` rechaza el claim ausente, y acá es aceptable. */
    private fun validadorDeVersion() = OAuth2TokenValidator<Jwt> { jwt ->
        if (versionValida(jwt.claims[CONTRACT_VERSION])) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(
                OAuth2Error(
                    "invalid_token",
                    "El token declara la versión de contrato '${jwt.claims[CONTRACT_VERSION]}' " +
                        "y este servicio entiende la $contractVersion.",
                    null
                )
            )
        }
    }

    private fun versionValida(valor: Any?): Boolean = when (valor) {
        null -> true
        is Number -> valor.toInt() == contractVersion
        else -> valor.toString() == contractVersion.toString()
    }

    internal companion object {
        const val TOKEN_USE = "token_use"
        const val CONTRACT_VERSION = "ver"
        const val SERVICE = "service"
    }
}
