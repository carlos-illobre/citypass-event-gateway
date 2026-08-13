package com.citypass.gateway.service

import com.citypass.gateway.model.Subscription
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.kotlin.*
import org.springframework.web.client.RestClient

class WebhookDeliveryServiceTest {

    private val dlqService: DlqService = mock()

    // Deep stubs para que toda la cadena fluente devuelva mocks sin lanzar NPE.
    private val restClient: RestClient = mock(defaultAnswer = RETURNS_DEEP_STUBS)

    // Acepta cualquier destino: lo que se prueba acá son los reintentos, no la validación.
    private val callbackUrlValidator: CallbackUrlValidator = mock()

    private val subscription = Subscription(
        topic = "test.topic", callbackUrl = "http://example.com/hook", owner = "com.citypass.movilidad"
    )
    private val event = mapOf<String, Any?>("eventId" to "uuid-1", "eventType" to "test.topic")

    private val registry = SimpleMeterRegistry()

    private fun service() = WebhookDeliveryService(dlqService, restClient, callbackUrlValidator, registry)

    /** Cuenta del contador de entregas para un resultado. */
    private fun entregas(resultado: String) =
        registry.counter("citypass.webhook.entregas", "topic", "test.topic", "resultado", resultado).count()

    @Test
    fun `deliverWithRetry succeeds on first attempt`() {
        // La cadena completa devuelve mocks por defecto — ningún paso lanza → éxito.
        service()
            .deliverWithRetry(subscription, event, maxRetries = 1, retryDelayMs = 0)

        verifyNoInteractions(dlqService)
        assertEquals(1.0, entregas("entregado"))
    }

    @Test
    fun `deliverWithRetry sends to DLQ after exhausting all retries`() {
        // Hacer que post() tire siempre → todos los intentos fallan → DLQ.
        whenever(restClient.post()).thenThrow(RuntimeException("connection refused"))

        service()
            .deliverWithRetry(subscription, event, maxRetries = 3, retryDelayMs = 0)

        verify(dlqService).sendWebhookFailure(
            originalTopic = eq("test.topic"),
            originalKey = isNull(),
            eventJson = eq(event),
            callbackUrl = eq("http://example.com/hook"),
            retryCount = eq(3),
            error = any(),
            owner = eq("com.citypass.movilidad")
        )
        assertEquals(1.0, entregas("agotado"))
    }

    @Test
    fun `deliverWithRetry retries and succeeds on second attempt`() {
        // Primer post() tira, segundo post() devuelve el deep stub (éxito silencioso).
        whenever(restClient.post())
            .thenThrow(RuntimeException("transient error"))
            .thenReturn(mock(defaultAnswer = RETURNS_DEEP_STUBS))

        service()
            .deliverWithRetry(subscription, event, maxRetries = 3, retryDelayMs = 0)

        verifyNoInteractions(dlqService)
    }

    @Test
    fun `deliverAll entrega a todos los suscriptores y no vuelve hasta terminar`() {
        val otra = subscription.copy(id = "s-2", callbackUrl = "http://otro.example.com/hook")

        service().deliverAll(listOf(subscription, otra), event)

        // Al volver ya está todo hecho: no hay sleep en el test porque no hay nada
        // pendiente en background. Eso es lo que permite confirmar el offset después.
        assertEquals(2.0, registry.counter(
            "citypass.webhook.entregas", "topic", "test.topic", "resultado", "entregado"
        ).count())
        verifyNoInteractions(dlqService)
    }

    @Test
    fun `deliverAll sin suscriptores no hace nada`() {
        service().deliverAll(emptyList(), event)

        verifyNoInteractions(dlqService)
        verifyNoInteractions(restClient)
    }

    @Test
    fun `deliverWithRetry with maxRetries zero does nothing`() {
        // Cubre la rama del for loop cuando el rango está vacío (1..0).
        service()
            .deliverWithRetry(subscription, event, maxRetries = 0, retryDelayMs = 0)

        verifyNoInteractions(dlqService)
        verifyNoInteractions(restClient)
    }

    @Test
    fun `un destino bloqueado no se contacta, va directo a la DLQ del dueño`() {
        whenever(callbackUrlValidator.reject("http://example.com/hook")).thenReturn("apunta a la red interna")

        service().deliverWithRetry(subscription, event, maxRetries = 3, retryDelayMs = 0)

        // Ni un solo POST: el chequeo corre antes de conectarse, no después.
        verifyNoInteractions(restClient)
        verify(dlqService).sendWebhookFailure(
            originalTopic = eq("test.topic"),
            originalKey = isNull(),
            eventJson = eq(event),
            callbackUrl = eq("http://example.com/hook"),
            retryCount = eq(0),
            error = any(),
            owner = eq("com.citypass.movilidad")
        )
        assertEquals(1.0, entregas("bloqueado"))
    }
}
