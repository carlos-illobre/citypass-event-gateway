package com.citypass.webhooks.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail

class ProblemsTest {

    @Test
    fun `arma un problem detail con estado, titulo y detalle`() {
        val respuesta = problem(HttpStatus.CONFLICT, "Hay equipos suscriptos", "Coordiná la baja.")
        val cuerpo = respuesta.body as ProblemDetail

        assertEquals(HttpStatus.CONFLICT, respuesta.statusCode)
        assertEquals("Hay equipos suscriptos", cuerpo.title)
        assertEquals("Coordiná la baja.", cuerpo.detail)
    }

    /** Los miembros de extensión son lo que permite nombrar a los suscriptores en el 409. */
    @Test
    fun `agrega los miembros de extension al cuerpo`() {
        val respuesta = problem(
            HttpStatus.CONFLICT, "Hay equipos suscriptos", "Coordiná la baja.",
            mapOf("subscribers" to listOf(mapOf("owner" to "com.citypass.reclamos")))
        )

        val propiedades = (respuesta.body as ProblemDetail).properties!!
        assertEquals(1, (propiedades["subscribers"] as List<*>).size)
    }
}
