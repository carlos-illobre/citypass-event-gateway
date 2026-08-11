package com.citypass.gateway.service

import com.citypass.gateway.model.Subscription
import org.junit.jupiter.api.Test
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.kotlin.*
import org.springframework.web.client.RestClient

class WebhookDeliveryServiceTest {

    private val dlqService: DlqService = mock()

    // Deep stubs para que toda la cadena fluente devuelva mocks sin lanzar NPE.
    private val restClient: RestClient = mock(defaultAnswer = RETURNS_DEEP_STUBS)

    private val subscription = Subscription(topic = "test.topic", callbackUrl = "http://example.com/hook")
    private val event = mapOf<String, Any?>("eventId" to "uuid-1", "eventType" to "test.topic")

    @Test
    fun `deliverWithRetry succeeds on first attempt`() {
        // La cadena completa devuelve mocks por defecto — ningún paso lanza → éxito.
        WebhookDeliveryService(dlqService, restClient)
            .deliverWithRetry(subscription, event, maxRetries = 1, retryDelayMs = 0)

        verifyNoInteractions(dlqService)
    }

    @Test
    fun `deliverWithRetry sends to DLQ after exhausting all retries`() {
        // Hacer que post() tire siempre → todos los intentos fallan → DLQ.
        whenever(restClient.post()).thenThrow(RuntimeException("connection refused"))

        WebhookDeliveryService(dlqService, restClient)
            .deliverWithRetry(subscription, event, maxRetries = 3, retryDelayMs = 0)

        verify(dlqService).sendWebhookFailure(
            originalTopic = eq("test.topic"),
            originalKey = isNull(),
            eventJson = eq(event),
            callbackUrl = eq("http://example.com/hook"),
            retryCount = eq(3),
            error = any()
        )
    }

    @Test
    fun `deliverWithRetry retries and succeeds on second attempt`() {
        // Primer post() tira, segundo post() devuelve el deep stub (éxito silencioso).
        whenever(restClient.post())
            .thenThrow(RuntimeException("transient error"))
            .thenReturn(mock(defaultAnswer = RETURNS_DEEP_STUBS))

        WebhookDeliveryService(dlqService, restClient)
            .deliverWithRetry(subscription, event, maxRetries = 3, retryDelayMs = 0)

        verifyNoInteractions(dlqService)
    }

    @Test
    fun `deliver submits task to virtual thread executor without throwing`() {
        WebhookDeliveryService(dlqService, restClient)
            .deliver(subscription, event)

        Thread.sleep(200)
        verifyNoInteractions(dlqService)
    }

    @Test
    fun `deliverWithRetry with maxRetries zero does nothing`() {
        // Cubre la rama del for loop cuando el rango está vacío (1..0).
        WebhookDeliveryService(dlqService, restClient)
            .deliverWithRetry(subscription, event, maxRetries = 0, retryDelayMs = 0)

        verifyNoInteractions(dlqService)
        verifyNoInteractions(restClient)
    }
}
