package com.citypass.gateway.web

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant

class RateLimitFilterTest {

    private val registry = SimpleMeterRegistry()

    @AfterEach
    fun limpiar() = SecurityContextHolder.clearContext()

    /** Deja en el contexto un token del namespace indicado, como haría el resource server. */
    private fun autenticarComo(namespace: String) {
        val jwt = Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject("usuario1")
            .claim("namespace", namespace)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    private fun pedir(filter: RateLimitFilter, cadena: FilterChain): MockHttpServletResponse {
        val respuesta = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("POST", "/api/v1/event-types/x/events"), respuesta, cadena)
        return respuesta
    }

    @Test
    fun `deja pasar hasta el limite y corta la siguiente`() {
        val filter = RateLimitFilter(limitePorMinuto = 3, meterRegistry = registry)
        val cadena: FilterChain = mock()
        autenticarComo("com.citypass.movilidad")

        repeat(3) { assertEquals(200, pedir(filter, cadena).status) }

        val cortada = pedir(filter, cadena)
        assertEquals(429, cortada.status)
        assertEquals("application/problem+json", cortada.contentType)
        // Sin Retry-After el cliente reintenta enseguida y agrava el problema.
        assertEquals("60", cortada.getHeader("Retry-After"))
        assertTrue(cortada.contentAsString.contains("com.citypass.movilidad"))

        // Sólo las 3 permitidas llegaron al controller.
        verify(cadena, times(3)).doFilter(any(), any())
        assertEquals(1.0, registry.counter("citypass.rate_limit.rechazos", "namespace", "com.citypass.movilidad").count())
    }

    @Test
    fun `la cuota de un grupo no consume la de otro`() {
        // Si la cuota fuera global, el loop de un grupo dejaría sin servicio a los demás,
        // que es exactamente lo que este filtro busca evitar.
        val filter = RateLimitFilter(limitePorMinuto = 1, meterRegistry = registry)
        val cadena: FilterChain = mock()

        autenticarComo("com.citypass.movilidad")
        assertEquals(200, pedir(filter, cadena).status)
        assertEquals(429, pedir(filter, cadena).status)

        autenticarComo("com.citypass.reclamos")
        assertEquals(200, pedir(filter, cadena).status)
    }

    @Test
    fun `una peticion sin token no se limita`() {
        // No hay namespace al que imputarle la cuota; la cadena de seguridad ya decide si
        // pasa o no. Las únicas rutas sin token son /health y los esquemas públicos.
        val filter = RateLimitFilter(limitePorMinuto = 0, meterRegistry = registry)
        val cadena: FilterChain = mock()

        assertEquals(200, pedir(filter, cadena).status)
        verify(cadena).doFilter(any(), any())
    }

    @Test
    fun `una autenticacion que no es un JWT no se limita`() {
        // Cubre el caso de un Authentication de otro tipo en el contexto.
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("u", "p")
        val filter = RateLimitFilter(limitePorMinuto = 0, meterRegistry = registry)
        val cadena: FilterChain = mock()

        assertEquals(200, pedir(filter, cadena).status)
    }

    @Test
    fun `un token sin claim namespace no se limita`() {
        val jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)

        val filter = RateLimitFilter(limitePorMinuto = 0, meterRegistry = registry)
        val cadena: FilterChain = mock()

        assertEquals(200, pedir(filter, cadena).status)
    }

    @Test
    fun `la cuota se renueva cuando vence la ventana`() {
        val filter = RateLimitFilter(limitePorMinuto = 1, meterRegistry = registry)
        val cadena: FilterChain = mock()
        autenticarComo("com.citypass.movilidad")

        assertEquals(200, pedir(filter, cadena).status)
        assertEquals(429, pedir(filter, cadena).status)

        // Se envejece la ventana en vez de esperar un minuto real.
        val campo = RateLimitFilter::class.java.getDeclaredField("ventanas").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val ventanas = campo.get(filter) as MutableMap<String, Any>
        val ventana = ventanas["com.citypass.movilidad"]!!
        val inicio = ventana.javaClass.getDeclaredField("inicio").apply { isAccessible = true }
        inicio.set(ventana, System.currentTimeMillis() - 61_000)

        assertEquals(200, pedir(filter, cadena).status)
    }
}
