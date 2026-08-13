package com.citypass.gateway.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventSelectionTest {

    private fun evento(source: String?, receivedAt: Long?, id: String): Map<String, Any?> {
        val metadata = buildMap<String, Any?> {
            if (source != null) put("source", source)
            if (receivedAt != null) put("receivedAt", receivedAt)
            put("eventId", id)
        }
        return mapOf("metadata" to metadata, "data" to mapOf("id" to id))
    }

    private fun ids(eventos: List<Map<String, Any?>>) =
        eventos.map { ((it["metadata"] as Map<*, *>)["eventId"]) }

    @Test
    fun `sólo devuelve los eventos del source pedido`() {
        // Los tópicos de un namespace son del grupo, pero los publican distintos usuarios:
        // sin este filtro, cada uno vería lo que mandaron los demás.
        val eventos = listOf(
            evento("usuario1", 100, "a"),
            evento("usuario2", 200, "b"),
            evento("usuario1", 300, "c"),
        )

        assertEquals(listOf("c", "a"), ids(EventSelection.propios(eventos, "usuario1", 10)))
    }

    @Test
    fun `ordena del más reciente al más antiguo`() {
        // Se leen varios tópicos a la vez y cada uno tiene su propio offset, así que los
        // eventos llegan mezclados: el orden lo recompone `receivedAt`.
        val eventos = listOf(
            evento("u", 200, "medio"),
            evento("u", 300, "nuevo"),
            evento("u", 100, "viejo"),
        )

        assertEquals(listOf("nuevo", "medio", "viejo"), ids(EventSelection.propios(eventos, "u", 10)))
    }

    @Test
    fun `respeta el límite y se queda con los más nuevos`() {
        val eventos = (1..5).map { evento("u", it * 100L, "e$it") }

        assertEquals(listOf("e5", "e4"), ids(EventSelection.propios(eventos, "u", 2)))
    }

    @Test
    fun `descarta un evento sin metadata`() {
        // No debería existir, pero mostrarlo sería atribuirle a alguien un evento que no
        // se puede atribuir.
        val eventos = listOf(mapOf<String, Any?>("data" to mapOf("id" to "x")), evento("u", 1, "ok"))

        assertEquals(listOf("ok"), ids(EventSelection.propios(eventos, "u", 10)))
    }

    @Test
    fun `descarta un evento sin source`() {
        val eventos = listOf(evento(null, 1, "sin-source"), evento("u", 1, "ok"))

        assertEquals(listOf("ok"), ids(EventSelection.propios(eventos, "u", 10)))
    }

    @Test
    fun `un evento sin receivedAt queda al final en vez de romper el orden`() {
        val eventos = listOf(evento("u", null, "sin-fecha"), evento("u", 100, "con-fecha"))

        assertEquals(listOf("con-fecha", "sin-fecha"), ids(EventSelection.propios(eventos, "u", 10)))
    }

    @Test
    fun `sin eventos devuelve una lista vacía`() {
        assertTrue(EventSelection.propios(emptyList(), "u", 10).isEmpty())
    }
}
