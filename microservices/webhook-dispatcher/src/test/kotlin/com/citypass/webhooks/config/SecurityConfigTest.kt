package com.citypass.webhooks.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

/**
 * Los validadores del token del dispatcher, armados a mano como en el `SecurityConfigTest`
 * del gateway: lo que interesa es qué rechaza cada uno. Acá se cubre lo que tiene que
 * coincidir con el gateway —la consola usa los dos servicios con el mismo token— y no se
 * repite cada caso de allá.
 */
class SecurityConfigTest {

    private val audiencia = "citypass"
    private val emisor = "https://idp.citypass.example"

    private val config = SecurityConfig(
        authServiceUrl = "http://localhost:8083",
        corsOrigin = "http://localhost:5173",
        audience = audiencia,
        issuer = emisor,
        contractVersion = 1,
        openapiEnabled = false
    )

    private fun token(
        aud: List<String> = listOf(audiencia),
        tokenUse: String? = SecurityConfig.SERVICE
    ): Jwt {
        val claims = buildMap<String, Any> {
            put("aud", aud)
            put("iss", emisor)
            put("namespace", "com.citypass.bus")
            put(SecurityConfig.CONTRACT_VERSION, 1)
            tokenUse?.let { put(SecurityConfig.TOKEN_USE, it) }
        }
        return Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .claims { it.putAll(claims) }
            .issuedAt(Instant.now().minusSeconds(60))
            .expiresAt(Instant.now().plusSeconds(900))
            .build()
    }

    private fun acepta(jwt: Jwt): Boolean =
        !DelegatingOAuth2TokenValidator(config.validadores()).validate(jwt).hasErrors()

    @Test
    fun `acepta un token de servicio`() {
        assertTrue(acepta(token()))
    }

    /** La consola gestiona suscripciones y la cola de fallidos con el token humano (ADR-025). */
    @Test
    fun `acepta un token humano`() {
        assertTrue(acepta(token(tokenUse = SecurityConfig.HUMAN)))
    }

    @Test
    fun `rechaza un token_use desconocido o ausente`() {
        assertFalse(acepta(token(tokenUse = "refresh")))
        assertFalse(acepta(token(tokenUse = null)))
    }

    /** Un token humano emitido por la app de otro módulo no es para el bus. */
    @Test
    fun `rechaza la audiencia de otro modulo`() {
        assertFalse(acepta(token(aud = listOf("citypass-reclamos-api"), tokenUse = SecurityConfig.HUMAN)))
    }
}
