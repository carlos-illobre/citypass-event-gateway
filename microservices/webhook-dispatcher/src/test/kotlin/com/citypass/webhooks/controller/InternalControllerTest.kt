package com.citypass.webhooks.controller

import com.citypass.webhooks.model.Subscription
import com.citypass.webhooks.service.SubscriptionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class InternalControllerTest {

    private val subscriptionService: SubscriptionService = mock()
    private val controller = InternalController(subscriptionService)

    /**
     * Sólo `owner` y `topic`: el gateway no necesita —ni debe recibir— la `callbackUrl` de
     * otro equipo para decidir si puede borrar un event type.
     */
    @Test
    fun `informa dueno y topico de cada suscriptor, y nada mas`() {
        whenever(subscriptionService.suscriptoresA(listOf("t1"))).thenReturn(listOf(
            Subscription(topic = "t1", callbackUrl = "https://secreto.example/hook", owner = "com.citypass.reclamos")
        ))

        val cuerpo = controller.suscriptores(listOf("t1")).body as List<*>

        assertEquals(listOf(mapOf("owner" to "com.citypass.reclamos", "topic" to "t1")), cuerpo)
    }

    @Test
    fun `sin suscriptores devuelve una lista vacia`() {
        whenever(subscriptionService.suscriptoresA(listOf("t1"))).thenReturn(emptyList())
        assertEquals(emptyList<Any>(), controller.suscriptores(listOf("t1")).body)
    }

    @Test
    fun `da de baja e informa cuantas quito`() {
        whenever(subscriptionService.unregisterTopics(listOf("t1", "t2"))).thenReturn(2)
        assertEquals(mapOf("removed" to 2), controller.borrar(listOf("t1", "t2")).body)
    }
}
