package com.citypass.gateway.web

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class PayloadSizeFilterTest {

    private val filter = PayloadSizeFilter(maxBytes = 100)

    private fun peticionDe(bytes: Int): MockHttpServletRequest =
        MockHttpServletRequest("POST", "/api/v1/event-types/x/events").apply {
            setContent(ByteArray(bytes))
        }

    @Test
    fun `un cuerpo dentro del limite pasa`() {
        val cadena: FilterChain = mock()
        val respuesta = MockHttpServletResponse()

        filter.doFilter(peticionDe(100), respuesta, cadena)

        verify(cadena).doFilter(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        assertEquals(200, respuesta.status)
    }

    @Test
    fun `un cuerpo mas grande se corta con 413 y no llega al controller`() {
        val cadena: FilterChain = mock()
        val respuesta = MockHttpServletResponse()

        filter.doFilter(peticionDe(101), respuesta, cadena)

        // Lo importante no es sólo el 413: el cuerpo nunca se procesa.
        verify(cadena, never()).doFilter(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        assertEquals(413, respuesta.status)
        assertEquals("application/problem+json", respuesta.contentType)
        assertTrue(respuesta.contentAsString.contains("100 bytes"))
    }

    @Test
    fun `una peticion sin cuerpo pasa`() {
        // Content-Length -1: un GET no declara longitud y no tiene por qué frenarse.
        val cadena: FilterChain = mock()
        val respuesta = MockHttpServletResponse()

        filter.doFilter(MockHttpServletRequest("GET", "/health"), respuesta, cadena)

        verify(cadena).doFilter(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        assertEquals(200, respuesta.status)
    }
}
