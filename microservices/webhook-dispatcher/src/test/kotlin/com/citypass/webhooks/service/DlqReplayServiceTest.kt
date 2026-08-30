package com.citypass.webhooks.service

import com.citypass.webhooks.model.Subscription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64

class DlqReplayServiceTest {

    private val subscriptionService: SubscriptionService = mock()
    private val webhookDeliveryService: WebhookDeliveryService = mock()
    private val dlqService: DlqService = mock()
    private val service = DlqReplayService(subscriptionService, webhookDeliveryService, dlqService)

    private val owner = "com.citypass.movilidad"
    private val mapper = jacksonObjectMapper()

    private val suscripcion = Subscription(
        id = "sub-1",
        topic = "com.citypass.movilidad.BiciDevuelta",
        callbackUrl = "https://destino.example/hook",
        owner = owner,
        createdBy = "grupo3"
    )

    private val evento = mapOf("data" to mapOf("biciId" to "b-1"))

    private fun entrada(
        dlqId: String? = "dlq-1",
        propietario: String? = owner,
        razon: String? = DlqService.WEBHOOK_DELIVERY_FAILED,
        idSuscripcion: String? = "sub-1",
        payload: Any? = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(evento))
    ): Map<String, Any?> = buildMap {
        put("dlqId", dlqId)
        put("owner", propietario)
        put("failureReason", razon)
        put("subscriptionId", idSuscripcion)
        put("originalPayloadBase64", payload)
    }

    // ── el camino feliz ───────────────────────────────────────────────────────

    @Test
    fun `reentrega, y marca la entrada como resuelta`() {
        whenever(subscriptionService.getById("sub-1")).thenReturn(suscripcion)
        whenever(webhookDeliveryService.deliverWithRetry(any(), any(), any(), any(), any())).thenReturn(true)

        val resultado = service.reentregar(entrada(), owner)

        assertInstanceOf(Reentrega.Entregada::class.java, resultado)
        assertEquals(suscripcion.callbackUrl, (resultado as Reentrega.Entregada).callbackUrl)
        verify(dlqService).marcarResuelto("dlq-1", owner)
    }

    /**
     * Si el destino vuelve a fallar, la entrada **no** se marca resuelta: tiene que seguir
     * apareciendo en el listado para poder reintentarla más tarde.
     */
    @Test
    fun `si vuelve a fallar, la entrada sigue pendiente`() {
        whenever(subscriptionService.getById("sub-1")).thenReturn(suscripcion)
        whenever(webhookDeliveryService.deliverWithRetry(any(), any(), any(), any(), any())).thenReturn(false)

        val resultado = service.reentregar(entrada(), owner)

        assertInstanceOf(Reentrega.Fallida::class.java, resultado)
        assertEquals(suscripcion.callbackUrl, (resultado as Reentrega.Fallida).callbackUrl)
        verify(dlqService, never()).marcarResuelto(any(), any())
    }

    @Test
    fun `una entrada silenciada tambien se puede reentregar`() {
        whenever(subscriptionService.getById("sub-1")).thenReturn(suscripcion)
        whenever(webhookDeliveryService.deliverWithRetry(any(), any(), any(), any(), any())).thenReturn(true)

        val resultado = service.reentregar(entrada(razon = DlqService.WEBHOOK_SILENCED), owner)

        assertInstanceOf(Reentrega.Entregada::class.java, resultado)
    }

    // ── lo que no se reentrega, y por qué ─────────────────────────────────────

    /** El control que impide que un grupo reentregue —y por lo tanto lea— lo de otro. */
    @Test
    fun `no reentrega una entrada de otro grupo`() {
        val resultado = service.reentregar(entrada(propietario = "com.citypass.reclamos"), owner)

        assertInstanceOf(Reentrega.NoAplica::class.java, resultado)
        verify(subscriptionService, never()).getById(any())
        verify(webhookDeliveryService, never()).deliverWithRetry(any(), any(), any(), any(), any())
    }

    @Test
    fun `no reentrega un fallo de deserializacion`() {
        val resultado = service.reentregar(entrada(razon = "DESERIALIZATION_ERROR"), owner)

        assertInstanceOf(Reentrega.NoAplica::class.java, resultado)
        val noAplica = resultado as Reentrega.NoAplica
        assertTrue(noAplica.motivo.contains("No es un fallo de entrega"))
        assertTrue(noAplica.detalle.contains("DESERIALIZATION_ERROR"))
    }

    /** Entradas anteriores a que se guardara el `subscriptionId`. */
    @Test
    fun `no reentrega una entrada sin suscripcion asociada`() {
        val resultado = service.reentregar(entrada(idSuscripcion = null), owner)
        assertInstanceOf(Reentrega.NoAplica::class.java, resultado)
    }

    @Test
    fun `no reentrega si la suscripcion fue dada de baja`() {
        whenever(subscriptionService.getById("sub-1")).thenReturn(null)

        val resultado = service.reentregar(entrada(), owner)

        assertInstanceOf(Reentrega.NoAplica::class.java, resultado)
        verify(webhookDeliveryService, never()).deliverWithRetry(any(), any(), any(), any(), any())
    }

    /**
     * El caso que justifica resolver el destino por `subscriptionId` y no por la URL
     * guardada: si la suscripción cambió de dueño, reenviar mandaría datos de un equipo a
     * un destino que ya no le pertenece.
     */
    @Test
    fun `no reentrega si la suscripcion cambio de dueno`() {
        whenever(subscriptionService.getById("sub-1"))
            .thenReturn(suscripcion.copy(owner = "com.citypass.reclamos"))

        val resultado = service.reentregar(entrada(), owner)

        assertInstanceOf(Reentrega.NoAplica::class.java, resultado)
        verify(webhookDeliveryService, never()).deliverWithRetry(any(), any(), any(), any(), any())
    }

    @Test
    fun `no reentrega si el payload no se puede decodificar`() {
        whenever(subscriptionService.getById("sub-1")).thenReturn(suscripcion)

        val resultado = service.reentregar(entrada(payload = "no-es-base64-valido!!"), owner)

        assertInstanceOf(Reentrega.NoAplica::class.java, resultado)
    }

    @Test
    fun `no reentrega si falta el payload`() {
        whenever(subscriptionService.getById("sub-1")).thenReturn(suscripcion)

        val resultado = service.reentregar(entrada(payload = null), owner)

        assertInstanceOf(Reentrega.NoAplica::class.java, resultado)
    }

    @Test
    fun `no reentrega si la entrada no tiene dlqId`() {
        whenever(subscriptionService.getById("sub-1")).thenReturn(suscripcion)

        val resultado = service.reentregar(entrada(dlqId = null), owner)

        assertInstanceOf(Reentrega.NoAplica::class.java, resultado)
        verify(webhookDeliveryService, never()).deliverWithRetry(any(), any(), any(), any(), any())
    }
}
