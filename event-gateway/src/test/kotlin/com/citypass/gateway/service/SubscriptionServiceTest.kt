package com.citypass.gateway.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.kafka.core.ConsumerFactory
import java.io.File

class SubscriptionServiceTest {

    private val consumerFactory: ConsumerFactory<String, ByteArray> = mock()
    private val avroDeserializerService: AvroDeserializerService = mock()
    private val webhookDeliveryService: WebhookDeliveryService = mock()
    private val dlqService: DlqService = mock()

    @TempDir
    lateinit var tempDir: File

    private lateinit var subscriptionService: SubscriptionService

    @BeforeEach
    fun setUp() {
        whenever(consumerFactory.configurationProperties).thenReturn(mapOf(
            "bootstrap.servers" to "localhost:9092",
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "org.apache.kafka.common.serialization.ByteArrayDeserializer",
            "group.id" to "test-group"
        ))
        subscriptionService = SubscriptionService(
            consumerFactory = consumerFactory,
            avroDeserializerService = avroDeserializerService,
            webhookDeliveryService = webhookDeliveryService,
            dlqService = dlqService,
            dataDir = tempDir.absolutePath
        )
    }

    @Test
    fun `register creates subscription and stores in memory`() {
        val sub = subscriptionService.register("topic.a", "http://localhost/hook")

        assertNotNull(sub.id)
        assertEquals("topic.a", sub.topic)
        assertEquals("http://localhost/hook", sub.callbackUrl)

        val all = subscriptionService.getAll()
        assertEquals(1, all.size)
        assertTrue(all.contains(sub))
    }

    @Test
    fun `unregister removes subscription and returns true`() {
        val sub = subscriptionService.register("topic.b", "http://localhost/hook2")
        val removed = subscriptionService.unregister(sub.id)

        assertTrue(removed)
        assertTrue(subscriptionService.getAll().isEmpty())
    }

    @Test
    fun `unregister returns false for non existent id`() {
        val removed = subscriptionService.unregister("non-existent-id")
        assertFalse(removed)
    }

    @Test
    fun `loadFromDisk handles empty directory gracefully`() {
        subscriptionService.loadFromDisk()
        assertTrue(subscriptionService.getAll().isEmpty())
    }
}
