package com.citypass.gateway.service

import com.citypass.gateway.model.Subscription
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.*
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.listener.MessageListener
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

class SubscriptionServiceTest {

    private val consumerFactory: ConsumerFactory<String, ByteArray> = mock()
    private val avroService: AvroService = mock()
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
            avroService = avroService,
            webhookDeliveryService = webhookDeliveryService,
            dlqService = dlqService,
            dataDir = tempDir.absolutePath
        )
    }

    // ── register / unregister / getAll ──────────────────────────────────────

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
    fun `register second subscription on same topic reuses existing consumer`() {
        subscriptionService.register("topic.shared", "http://localhost/hook1")
        subscriptionService.register("topic.shared", "http://localhost/hook2")
        assertEquals(2, subscriptionService.getAll().size)
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
    fun `unregister keeps consumer when another subscriber remains on same topic`() {
        val sub1 = subscriptionService.register("topic.shared", "http://localhost/hook1")
        subscriptionService.register("topic.shared", "http://localhost/hook2")

        val removed = subscriptionService.unregister(sub1.id)

        assertTrue(removed)
        assertEquals(1, subscriptionService.getAll().size)
    }

    // ── loadFromDisk ─────────────────────────────────────────────────────────

    @Test
    fun `loadFromDisk handles empty directory gracefully`() {
        subscriptionService.loadFromDisk()
        assertTrue(subscriptionService.getAll().isEmpty())
    }

    @Test
    fun `loadFromDisk restores subscriptions persisted to disk`() {
        val sub1 = Subscription(topic = "topic.a", callbackUrl = "http://localhost/hook1")
        val sub2 = Subscription(topic = "topic.b", callbackUrl = "http://localhost/hook2")
        val mapper = jacksonObjectMapper()
        File(tempDir, "subscriptions.json").writeText(mapper.writeValueAsString(listOf(sub1, sub2)))

        subscriptionService.loadFromDisk()

        assertEquals(2, subscriptionService.getAll().size)
    }

    @Test
    fun `loadFromDisk handles malformed json gracefully`() {
        File(tempDir, "subscriptions.json").writeText("not valid json [[[")

        assertDoesNotThrow { subscriptionService.loadFromDisk() }
        assertTrue(subscriptionService.getAll().isEmpty())
    }

    @Test
    fun `register handles disk write failure gracefully`() {
        // Make tempDir read-only to force saveToDisk IOException
        tempDir.setReadOnly()
        try {
            assertDoesNotThrow { subscriptionService.register("topic.x", "http://example.com") }
        } finally {
            tempDir.setWritable(true)
        }
    }

    // ── message listener wiring ──────────────────────────────────────────────

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `ensureConsumer message listener delegates to dispatch`() {
        subscriptionService.register("topic.wired", "http://localhost/hook")
        val rawBytes = byteArrayOf(9, 8, 7)
        val event = mapOf<String, Any?>("eventId" to "e-wired")
        whenever(avroService.deserialize(rawBytes)).thenReturn(event)

        val container = subscriptionService.containers["topic.wired"]!!
        val listener = container.containerProperties.messageListener as MessageListener<String, ByteArray>
        val record = ConsumerRecord<String, ByteArray>("topic.wired", 0, 0L, "k", rawBytes)
        listener.onMessage(record)

        verify(webhookDeliveryService).deliver(any(), eq(event))
    }

    // ── dispatch ─────────────────────────────────────────────────────────────

    @Test
    fun `dispatch does nothing when no subscribers for topic`() {
        val record = ConsumerRecord<String, ByteArray>("topic.a", 0, 0L, "key", byteArrayOf())
        subscriptionService.dispatch(record)
        verifyNoInteractions(avroService, webhookDeliveryService, dlqService)
    }

    @Test
    fun `dispatch delivers deserialized event to all subscribers of topic`() {
        val sub = subscriptionService.register("topic.a", "http://localhost/hook")
        val rawBytes = byteArrayOf(1, 2, 3)
        val event = mapOf<String, Any?>("eventId" to "uuid-1")
        whenever(avroService.deserialize(rawBytes)).thenReturn(event)

        val record = ConsumerRecord<String, ByteArray>("topic.a", 0, 0L, "key", rawBytes)
        subscriptionService.dispatch(record)

        verify(webhookDeliveryService).deliver(sub, event)
    }

    @Test
    fun `dispatch sends to DLQ when deserialization fails`() {
        subscriptionService.register("topic.a", "http://localhost/hook")
        val rawBytes = byteArrayOf(1, 2, 3)
        val exception = RuntimeException("bad avro")
        whenever(avroService.deserialize(rawBytes)).thenThrow(exception)

        val record = ConsumerRecord<String, ByteArray>("topic.a", 0, 0L, "key-1", rawBytes)
        subscriptionService.dispatch(record)

        verify(dlqService).sendDeserializationFailure("topic.a", "key-1", rawBytes, exception)
        verifyNoInteractions(webhookDeliveryService)
    }
}
