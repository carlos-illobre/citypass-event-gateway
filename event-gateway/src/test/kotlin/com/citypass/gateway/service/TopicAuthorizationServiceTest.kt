package com.citypass.gateway.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.security.oauth2.jwt.Jwt

class TopicAuthorizationServiceTest {

    private val service = TopicAuthorizationService()

    @Test
    fun `isAllowed returns false when jwt is null`() {
        assertFalse(service.isAllowed(null, "com.citypass.movilidad.BiciDevuelta"))
    }

    @Test
    fun `isAllowed returns false when namespace claim is missing`() {
        val jwt = jwtWithNamespace(null)
        assertFalse(service.isAllowed(jwt, "com.citypass.movilidad.BiciDevuelta"))
    }

    @Test
    fun `isAllowed returns true when namespace is wildcard star`() {
        val jwt = jwtWithNamespace("*")
        assertTrue(service.isAllowed(jwt, "com.citypass.movilidad.BiciDevuelta"))
    }

    @Test
    fun `isAllowed returns true when topic starts with namespace`() {
        val jwt = jwtWithNamespace("com.citypass.movilidad")
        assertTrue(service.isAllowed(jwt, "com.citypass.movilidad.BiciDevuelta"))
    }

    @Test
    fun `isAllowed returns false when topic belongs to different namespace`() {
        val jwt = jwtWithNamespace("com.citypass.movilidad")
        assertFalse(service.isAllowed(jwt, "com.citypass.reclamos.ReclamoCreado"))
    }

    @Test
    fun `isAllowed returns false when topic equals namespace without dot separator`() {
        val jwt = jwtWithNamespace("com.citypass.movilidad")
        assertFalse(service.isAllowed(jwt, "com.citypass.movilidad"))
    }

    @Test
    fun `isAllowed returns false when namespace is prefix but without dot`() {
        val jwt = jwtWithNamespace("com.citypass.movil")
        assertFalse(service.isAllowed(jwt, "com.citypass.movilidad.BiciDevuelta"))
    }

    private fun jwtWithNamespace(namespace: String?): Jwt {
        val jwt: Jwt = mock()
        val claims: Map<String, Any> = if (namespace != null) mapOf("namespace" to namespace) else emptyMap()
        whenever(jwt.claims).thenReturn(claims)
        return jwt
    }
}
