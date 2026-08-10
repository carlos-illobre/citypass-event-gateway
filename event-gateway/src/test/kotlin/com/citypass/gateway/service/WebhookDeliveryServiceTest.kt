package com.citypass.gateway.service

import com.citypass.gateway.model.Subscription
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class WebhookDeliveryServiceTest {

    private val dlqService: DlqService = mock()
    private val webhookDeliveryService = WebhookDeliveryService(dlqService)

    @Test
    fun `deliver method executes without throwing exception`() {
        val sub = Subscription(topic = "test.topic", callbackUrl = "http://invalid-host-for-testing-1234.local/webhook")
        val event = mapOf<String, Any?>("key" to "value")

        assertDoesNotThrow {
            webhookDeliveryService.deliver(sub, event)
            Thread.sleep(100) // allow executor to execute
        }
    }
}
