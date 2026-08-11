package com.citypass.gateway.service

import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.kafka.core.KafkaTemplate

class DlqServiceTest {

    private val kafkaTemplate: KafkaTemplate<String, ByteArray> = mock()
    private val dlqService = DlqService(kafkaTemplate, "sistema.dlq")

    @Test
    fun `sendDeserializationFailure publishes message to DLQ topic`() {
        val rawBytes = "corrupted data".toByteArray()
        dlqService.sendDeserializationFailure("movilidad.bici.devuelta", "key-1", rawBytes, RuntimeException("bad avro"))

        verify(kafkaTemplate).send(any<ProducerRecord<String, ByteArray>>())
    }

    @Test
    fun `sendDeserializationFailure works when key is null`() {
        val rawBytes = byteArrayOf(0x01, 0x02)
        dlqService.sendDeserializationFailure("reclamos.creado", null, rawBytes, IllegalStateException("schema not found"))

        verify(kafkaTemplate).send(any<ProducerRecord<String, ByteArray>>())
    }

    @Test
    fun `sendWebhookFailure publishes message to DLQ topic`() {
        val eventJson = mapOf<String, Any?>("eventId" to "uuid-123", "eventType" to "reclamos.creado")
        dlqService.sendWebhookFailure(
            originalTopic = "reclamos.creado",
            originalKey = "key-2",
            eventJson = eventJson,
            callbackUrl = "http://example.com/webhook",
            retryCount = 3,
            error = RuntimeException("connection refused")
        )

        verify(kafkaTemplate).send(any<ProducerRecord<String, ByteArray>>())
    }

    @Test
    fun `sendWebhookFailure works when originalKey is null`() {
        val eventJson = mapOf<String, Any?>("eventId" to "uuid-456")
        dlqService.sendWebhookFailure(
            originalTopic = "pagos.procesado",
            originalKey = null,
            eventJson = eventJson,
            callbackUrl = "http://example.com/webhook",
            retryCount = 3,
            error = RuntimeException("timeout")
        )

        verify(kafkaTemplate).send(any<ProducerRecord<String, ByteArray>>())
    }

    @Test
    fun `sendDeserializationFailure handles exception with null message`() {
        // Cubre la rama error.message ?: "Unknown error" cuando el mensaje es null.
        val rawBytes = byteArrayOf(0x00)
        dlqService.sendDeserializationFailure("test.topic", null, rawBytes, RuntimeException())

        verify(kafkaTemplate).send(any<ProducerRecord<String, ByteArray>>())
    }
}
