package com.citypass.webhooks.controller

import com.citypass.webhooks.service.DlqReader
import com.citypass.webhooks.service.DlqReplayService
import com.citypass.webhooks.service.DlqService
import com.citypass.webhooks.service.Reentrega
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

/**
 * Estas pruebas existen porque el controller dejó de crear consumers: mientras leía Kafka
 * él mismo estaba excluido de la cobertura, y con él quedaban sin medir el 404 de una
 * entrada inexistente y los tres desenlaces de un reintento.
 */
class DeadLetterControllerTest {

    private val dlqTopic = "sistema.webhooks-dlq"
    private val resueltosTopic = "sistema.webhooks-dlq-resueltos"

    private val dlqReader: DlqReader = mock()
    private val dlqService: DlqService = mock()
    private val dlqReplayService: DlqReplayService = mock()
    private val controller = DeadLetterController(dlqTopic, resueltosTopic, dlqReader, dlqService, dlqReplayService)

    private fun token(vararg claims: Pair<String, Any>): Jwt = Jwt.withTokenValue("t")
        .header("alg", "RS256")
        .claim("sub", "grupo3")
        .also { b -> claims.forEach { (k, v) -> b.claim(k, v) } }
        .issuedAt(Instant.EPOCH)
        .expiresAt(Instant.EPOCH.plusSeconds(3600))
        .build()

    private val conNamespace = token("namespace" to "com.citypass.movilidad")
    private val sinNamespace = token("otro" to "cosa")

    private fun entrada(id: String) = mapOf("dlqId" to id, "owner" to "com.citypass.movilidad")

    // ── listado ───────────────────────────────────────────────────────────────

    @Test
    fun `devuelve las entradas que el servicio considera visibles para el grupo`() {
        whenever(dlqReader.ultimosMensajes(eq(dlqTopic), any())).thenReturn(listOf(entrada("a")))
        whenever(dlqReader.ultimosMensajes(eq(resueltosTopic), any())).thenReturn(listOf(mapOf("dlqId" to "b")))
        whenever(dlqService.visiblesPara(any(), any(), any(), any())).thenReturn(listOf(entrada("a")))

        val cuerpo = controller.getMessages(50, conNamespace).body as Map<*, *>

        assertEquals(dlqTopic, cuerpo["topic"])
        assertEquals(1, cuerpo["returned"])
        assertEquals(listOf(entrada("a")), cuerpo["messages"])
    }

    /**
     * El tópico de resueltos es lo que hace que una entrada reentregada deje de aparecer.
     * Si el controller no lo leyera, el listado seguiría mostrando lo ya resuelto.
     */
    @Test
    fun `pasa al servicio los ids ya resueltos`() {
        whenever(dlqReader.ultimosMensajes(eq(dlqTopic), any())).thenReturn(listOf(entrada("a")))
        whenever(dlqReader.ultimosMensajes(eq(resueltosTopic), any()))
            .thenReturn(listOf(mapOf("dlqId" to "a"), mapOf("sin" to "id"), "no es un mapa"))
        whenever(dlqService.visiblesPara(any(), any(), any(), any())).thenReturn(emptyList())

        controller.getMessages(50, conNamespace)

        verify(dlqService).visiblesPara(listOf(entrada("a")), "com.citypass.movilidad", setOf("a"), 50)
    }

    /** El tope protege al broker: un `limit` enorme no puede convertirse en la lectura entera. */
    @Test
    fun `el limite pedido se recorta al maximo`() {
        whenever(dlqReader.ultimosMensajes(any(), any())).thenReturn(emptyList())
        whenever(dlqService.visiblesPara(any(), any(), any(), any())).thenReturn(emptyList())

        controller.getMessages(9999, conNamespace)

        verify(dlqService).visiblesPara(emptyList(), "com.citypass.movilidad", emptySet(), 200)
    }

    @Test
    fun `sin namespace en el token no se lista nada`() {
        val respuesta = controller.getMessages(50, sinNamespace)
        assertEquals(HttpStatus.BAD_REQUEST, respuesta.statusCode)
    }

    // ── reintento ─────────────────────────────────────────────────────────────

    @Test
    fun `sin namespace en el token no se reintenta nada`() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.reintentar("a", sinNamespace).statusCode)
    }

    @Test
    fun `un dlqId que no esta en la ventana leida da 404`() {
        whenever(dlqReader.ultimosMensajes(eq(dlqTopic), any())).thenReturn(listOf(entrada("otra"), "texto suelto"))

        assertEquals(HttpStatus.NOT_FOUND, controller.reintentar("a", conNamespace).statusCode)
    }

    @Test
    fun `una reentrega exitosa devuelve 200 con el destino usado`() {
        whenever(dlqReader.ultimosMensajes(eq(dlqTopic), any())).thenReturn(listOf(entrada("a")))
        whenever(dlqReplayService.reentregar(entrada("a"), "com.citypass.movilidad"))
            .thenReturn(Reentrega.Entregada("https://destino.example/hook"))

        val respuesta = controller.reintentar("a", conNamespace)
        val cuerpo = respuesta.body as Map<*, *>

        assertEquals(HttpStatus.OK, respuesta.statusCode)
        assertEquals("entregado", cuerpo["estado"])
        assertEquals("https://destino.example/hook", cuerpo["callbackUrl"])
    }

    /**
     * 502 y no 500: el que falló fue el destino ajeno, no el dispatcher, y la entrada
     * queda pendiente para volver a intentarse.
     */
    @Test
    fun `si el destino vuelve a fallar responde 502 y la entrada sigue pendiente`() {
        whenever(dlqReader.ultimosMensajes(eq(dlqTopic), any())).thenReturn(listOf(entrada("a")))
        whenever(dlqReplayService.reentregar(any(), any()))
            .thenReturn(Reentrega.Fallida("https://destino.example/hook"))

        val respuesta = controller.reintentar("a", conNamespace)
        val cuerpo = respuesta.body as Map<*, *>

        assertEquals(HttpStatus.BAD_GATEWAY, respuesta.statusCode)
        assertEquals("fallido", cuerpo["estado"])
    }

    @Test
    fun `si la reentrega no aplica responde 409 con el motivo`() {
        whenever(dlqReader.ultimosMensajes(eq(dlqTopic), any())).thenReturn(listOf(entrada("a")))
        whenever(dlqReplayService.reentregar(any(), any()))
            .thenReturn(Reentrega.NoAplica("Sin suscripción vigente", "La suscripción se dio de baja."))

        val respuesta = controller.reintentar("a", conNamespace)

        assertEquals(HttpStatus.CONFLICT, respuesta.statusCode)
        assertEquals("Sin suscripción vigente", (respuesta.body as ProblemDetail).title)
    }

    /**
     * Se toma la última: la DLQ es un log, así que un mismo `dlqId` puede aparecer más de
     * una vez y la que vale es la más reciente.
     */
    @Test
    fun `con el mismo dlqId repetido reentrega la ultima`() {
        val vieja = mapOf("dlqId" to "a", "intento" to 1)
        val nueva = mapOf("dlqId" to "a", "intento" to 2)
        whenever(dlqReader.ultimosMensajes(eq(dlqTopic), any())).thenReturn(listOf(vieja, nueva))
        whenever(dlqReplayService.reentregar(nueva, "com.citypass.movilidad"))
            .thenReturn(Reentrega.Entregada("https://destino.example/hook"))

        assertEquals(HttpStatus.OK, controller.reintentar("a", conNamespace).statusCode)
        verify(dlqReplayService).reentregar(nueva, "com.citypass.movilidad")
    }
}
