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

@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${gateway.auth-service-url}") private val authServiceUrl: String,
    @Value("\${gateway.cors-origin}") private val corsOrigin: String,
    @Value("\${gateway.token-audience}") private val audience: String,
    @Value("\${gateway.token-issuer:}") private val issuer: String,
    @Value("\${gateway.token-contract-version:1}") private val contractVersion: Int,
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
                            // `/doc` es la ruta configurada; springdoc redirige desde ahí
                            // a los recursos estáticos de swagger-ui, que también hay que abrir.
                            requestMatchers("/doc", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
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
     * Valida firma, vencimiento, emisor, audiencia, tipo de token y versión del contrato.
     *
     * Cada validador está por un motivo distinto, y ninguno cubre lo que cubre otro:
     *
     * - **`aud`** dice para quién se emitió el token. Sin este chequeo, un token que el
     *   mismo emisor generó para otro destinatario serviría acá. Llega como lista aunque
     *   tenga un solo elemento, así que se pregunta si la nuestra está adentro y no si es
     *   igual.
     * - **`iss`** dice quién lo emitió, y se compara literalmente. Es lo que impide que un
     *   emisor distinto que llegue a estar en el JWKS —una rotación mal hecha, un segundo
     *   entorno— pase por el legítimo.
     * - **`token_use`** separa las credenciales de personas de las de servicios. Publicar
     *   en el bus es cosa de un backend: la persona se autentica contra la API de su
     *   módulo, y su identidad viaja como dato del evento. Hoy un token humano ya fallaría
     *   por no traer `namespace`, pero eso es un accidente, no un control: el día que el
     *   emisor agregue ese claim a los tokens de personas, esto es lo único que evita que
     *   alguien publique salteándose esa frontera.
     * - **`ver`** es la versión del contrato de identidad. Rechazar lo que no se entiende
     *   es preferible a interpretarlo con reglas de otra versión.
     *
     * `token-issuer` vacío desactiva su chequeo, para no romper despliegues cuyo emisor
     * todavía no lo emite. Es una concesión temporal y debería quedar siempre configurado.
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
                "gateway.token-issuer está vacío: no se valida el emisor de los tokens. " +
                    "Configuralo apenas el servicio de identidad emita el claim 'iss'."
            )
        }
    }

    /**
     * Valida la versión del contrato, tolerando que **falte**.
     *
     * No se usa `JwtClaimValidator` porque rechaza el claim ausente antes de llegar al
     * predicado, y acá la ausencia es aceptable: hay emisores que todavía no mandan `ver`,
     * y rechazar por su falta dejaría al gateway sin poder validar nada más. Lo que no se
     * acepta es una versión **distinta** de la que este código entiende.
     */
    private fun validadorDeVersion() = OAuth2TokenValidator<Jwt> { jwt ->
        if (versionValida(jwt.claims[CONTRACT_VERSION])) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(
                OAuth2Error(
                    "invalid_token",
                    "El token declara la versión de contrato '${jwt.claims[CONTRACT_VERSION]}' " +
                        "y este gateway entiende la $contractVersion.",
                    null
                )
            )
        }
    }

    /** Tolera que la versión llegue como número o como texto: hay emisores que la serializan. */
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
