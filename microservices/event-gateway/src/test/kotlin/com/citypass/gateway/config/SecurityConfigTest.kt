package com.citypass.gateway.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

/**
 * Comprueba los validadores del token, uno por uno y por separado.
 *
 * Se arman a mano en vez de levantar el contexto porque lo que interesa es **qué rechaza
 * cada uno**: un test que sólo mire que el bean se construye pasaría igual si alguien
 * borrara un validador entero.
 */
class SecurityConfigTest {

    private val audiencia = "citypass"
    private val emisor = "https://idp.citypass.example"

    private fun config(issuer: String = emisor, version: Int = 1) = SecurityConfig(
        authServiceUrl = "http://localhost:8083",
        corsOrigin = "http://localhost:5173",
        audience = audiencia,
        issuer = issuer,
        contractVersion = version,
        openapiEnabled = false
    )

    /** Un token de servicio bien formado, sobre el que cada test cambia una sola cosa. */
    private fun token(
        aud: List<String> = listOf(audiencia),
        iss: String? = emisor,
        tokenUse: String? = SecurityConfig.SERVICE,
        ver: Any? = 1
    ): Jwt {
        val claims = buildMap<String, Any> {
            put("aud", aud)
            put("namespace", "com.citypass.bus")
            iss?.let { put("iss", it) }
            tokenUse?.let { put(SecurityConfig.TOKEN_USE, it) }
            ver?.let { put(SecurityConfig.CONTRACT_VERSION, it) }
        }
        return Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .claims { it.putAll(claims) }
            .issuedAt(Instant.now().minusSeconds(60))
            .expiresAt(Instant.now().plusSeconds(900))
            .build()
    }

    private fun acepta(jwt: Jwt, config: SecurityConfig = config()): Boolean =
        !DelegatingOAuth2TokenValidator(config.validadores()).validate(jwt).hasErrors()

    // ── audiencia ─────────────────────────────────────────────────────────────

    @Test
    fun `acepta un token de servicio bien formado`() {
        assertTrue(acepta(token()))
    }

    /** Llega como lista aunque tenga un solo elemento: se pregunta si la nuestra está adentro. */
    @Test
    fun `acepta la audiencia propia aunque venga acompanada de otras`() {
        assertTrue(acepta(token(aud = listOf("otra-api", audiencia))))
    }

    @Test
    fun `rechaza un token emitido para otra audiencia`() {
        assertFalse(acepta(token(aud = listOf("citypass-reclamos-api"))))
    }

    // ── emisor ────────────────────────────────────────────────────────────────

    @Test
    fun `rechaza un token de otro emisor`() {
        assertFalse(acepta(token(iss = "https://idp.impostor.example")))
    }

    /**
     * La comparación es literal: un emisor que *empieza* con el esperado no alcanza, o
     * `https://idp.citypass.example.attacker.test` pasaría.
     */
    @Test
    fun `la comparacion del emisor es exacta, no por prefijo`() {
        assertFalse(acepta(token(iss = "$emisor.attacker.test")))
    }

    @Test
    fun `rechaza un token sin emisor cuando hay uno configurado`() {
        assertFalse(acepta(token(iss = null)))
    }

    /** Concesión temporal para un emisor que todavía no manda `iss`. Ver el comentario del bean. */
    @Test
    fun `con token-issuer vacio no se valida el emisor`() {
        assertTrue(acepta(token(iss = null), config(issuer = "")))
        assertTrue(acepta(token(iss = "https://cualquiera.example"), config(issuer = "")))
    }

    // ── tipo de token ─────────────────────────────────────────────────────────

    /**
     * La frontera del ADR-011: publicar es cosa de un backend. Hoy un token humano además
     * fallaría por no traer `namespace`, pero eso es un accidente y esto es el control.
     */
    @Test
    fun `rechaza un token humano`() {
        assertFalse(acepta(token(tokenUse = "human")))
    }

    @Test
    fun `rechaza un token sin token_use`() {
        assertFalse(acepta(token(tokenUse = null)))
    }

    // ── versión del contrato ──────────────────────────────────────────────────

    @Test
    fun `rechaza una version de contrato distinta`() {
        assertFalse(acepta(token(ver = 2)))
    }

    /** Algunos emisores serializan los números del payload como texto. */
    @Test
    fun `acepta la version como texto`() {
        assertTrue(acepta(token(ver = "1")))
    }

    @Test
    fun `rechaza una version como texto que no coincide`() {
        assertFalse(acepta(token(ver = "2")))
    }

    /**
     * Un `ver` ausente se acepta: hay emisores que todavía no lo mandan, y rechazar por su
     * falta dejaría al gateway sin poder validar nada más.
     */
    @Test
    fun `acepta un token sin version de contrato`() {
        assertTrue(acepta(token(ver = null)))
    }

    @Test
    fun `respeta la version configurada`() {
        assertTrue(acepta(token(ver = 2), config(version = 2)))
        assertFalse(acepta(token(ver = 1), config(version = 2)))
    }

    // ── vencimiento ───────────────────────────────────────────────────────────

    @Test
    fun `rechaza un token vencido`() {
        val vencido = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .claim("aud", listOf(audiencia))
            .claim("iss", emisor)
            .claim(SecurityConfig.TOKEN_USE, SecurityConfig.SERVICE)
            .claim(SecurityConfig.CONTRACT_VERSION, 1)
            .issuedAt(Instant.now().minusSeconds(3600))
            .expiresAt(Instant.now().minusSeconds(1800))
            .build()

        assertFalse(acepta(vencido))
    }

    // ── el bean ───────────────────────────────────────────────────────────────

    @Test
    fun `el decoder se construye`() {
        assertTrue(config().jwtDecoder() is org.springframework.security.oauth2.jwt.NimbusJwtDecoder)
    }
}
