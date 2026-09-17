package com.citypass.webhooks.service

import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

class DlqServiceTest {

    private val kafkaTemplate: KafkaTemplate<String, ByteArray> = mock()
    private val dlqService = DlqService(kafkaTemplate, "sistema.dlq", "sistema.dlq-resueltos")

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
            subscriptionId = "sub-1", callbackUrl = "http://example.com/webhook",
            retryCount = 3,
            error = RuntimeException("connection refused"),
            owner = "com.citypass.movilidad"
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
            subscriptionId = "sub-1", callbackUrl = "http://example.com/webhook",
            retryCount = 3,
            error = RuntimeException("timeout"),
            owner = "com.citypass.movilidad"
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

    @Test
    fun `el owner de un fallo de deserializacion es el namespace del topico`() {
        val captor = argumentCaptor<ProducerRecord<String, ByteArray>>()
        dlqService.sendDeserializationFailure(
            "com.citypass.movilidad.BiciDevuelta", null, byteArrayOf(0x00), RuntimeException("bad avro")
        )

        verify(kafkaTemplate).send(captor.capture())
        val mensaje = jacksonObjectMapper().readValue(captor.firstValue.value(), Map::class.java)
        assertEquals("com.citypass.movilidad", mensaje["owner"])
    }

    @Test
    fun `un topico sin punto deja el owner vacio y no se lo atribuye a nadie`() {
        // Un tópico así no debería existir, pero si existiera, adivinar un dueño sería
        // peor que no mostrarle la entrada a ningún grupo.
        val captor = argumentCaptor<ProducerRecord<String, ByteArray>>()
        dlqService.sendDeserializationFailure("sinpuntos", null, byteArrayOf(0x00), RuntimeException("x"))

        verify(kafkaTemplate).send(captor.capture())
        val mensaje = jacksonObjectMapper().readValue(captor.firstValue.value(), Map::class.java)
        assertEquals("", mensaje["owner"])
    }

    /** El JSON del último registro publicado en la DLQ. */
    @Suppress("UNCHECKED_CAST")
    private fun capturarJson(): Map<String, Any?> {
        val captor = argumentCaptor<ProducerRecord<String, ByteArray>>()
        verify(kafkaTemplate).send(captor.capture())
        return jacksonObjectMapper().readValue(captor.firstValue.value(), Map::class.java) as Map<String, Any?>
    }

    // ── silenciado, resolución y visibilidad ──────────────────────────────────

    @Test
    fun `sendWebhookSilenciado registra el evento que no se intento entregar`() {
        dlqService.sendWebhookSilenciado(
            originalTopic = "com.citypass.movilidad.BiciDevuelta",
            originalKey = null,
            eventJson = mapOf("data" to mapOf("x" to 1)),
            subscriptionId = "sub-1",
            callbackUrl = "https://destino.example/hook",
            hasta = Instant.parse("2026-08-19T10:00:00Z"),
            owner = "com.citypass.movilidad"
        )

        val enviado = capturarJson()
        assertEquals(DlqService.WEBHOOK_SILENCED, enviado["failureReason"])
        assertEquals("sub-1", enviado["subscriptionId"])
        assertEquals("https://destino.example/hook", enviado["callbackUrl"])
    }

    /** La URL va como campo y no sólo dentro del texto del error: la reentrega la necesita. */
    @Test
    fun `sendWebhookFailure guarda la suscripcion y la url como campos`() {
        dlqService.sendWebhookFailure(
            originalTopic = "com.citypass.movilidad.BiciDevuelta",
            originalKey = null,
            eventJson = mapOf("data" to mapOf("x" to 1)),
            subscriptionId = "sub-9",
            callbackUrl = "https://destino.example/hook",
            retryCount = 3,
            error = RuntimeException("connection refused"),
            owner = "com.citypass.movilidad"
        )

        val enviado = capturarJson()
        assertEquals("sub-9", enviado["subscriptionId"])
        assertEquals("https://destino.example/hook", enviado["callbackUrl"])
    }

    @Test
    fun `marcarResuelto publica en el topico de resueltos con el dlqId como clave`() {
        dlqService.marcarResuelto("dlq-1", "com.citypass.movilidad")

        val captor = argumentCaptor<ProducerRecord<String, ByteArray>>()
        verify(kafkaTemplate).send(captor.capture())
        assertEquals("sistema.dlq-resueltos", captor.firstValue.topic())
        assertEquals("dlq-1", captor.firstValue.key())
    }

    // ── visiblesPara ─────────────────────────────────────────────────────────

    private fun entrada(id: String, owner: String) =
        mapOf("dlqId" to id, "owner" to owner)

    @Test
    fun `visiblesPara deja sólo las del grupo que consulta`() {
        val visibles = dlqService.visiblesPara(
            entradas = listOf(entrada("a", "com.citypass.movilidad"), entrada("b", "com.citypass.reclamos")),
            owner = "com.citypass.movilidad", resueltos = emptySet(), limit = 10
        )
        assertEquals(listOf(entrada("a", "com.citypass.movilidad")), visibles)
    }

    @Test
    fun `visiblesPara descarta las ya reentregadas`() {
        val visibles = dlqService.visiblesPara(
            entradas = listOf(entrada("a", "com.citypass.movilidad"), entrada("b", "com.citypass.movilidad")),
            owner = "com.citypass.movilidad", resueltos = setOf("a"), limit = 10
        )
        assertEquals(listOf(entrada("b", "com.citypass.movilidad")), visibles)
    }

    /** El tópico puede tener registros que no parsean: no se muestran ni rompen el listado. */
    @Test
    fun `visiblesPara ignora lo que no es un registro`() {
        val visibles = dlqService.visiblesPara(
            entradas = listOf("texto suelto", entrada("a", "com.citypass.movilidad")),
            owner = "com.citypass.movilidad", resueltos = emptySet(), limit = 10
        )
        assertEquals(1, visibles.size)
    }

    @Test
    fun `visiblesPara respeta el limite quedandose con las mas nuevas`() {
        val visibles = dlqService.visiblesPara(
            entradas = (1..5).map { entrada("e$it", "com.citypass.movilidad") },
            owner = "com.citypass.movilidad", resueltos = emptySet(), limit = 2
        )
        assertEquals(listOf(entrada("e4", "com.citypass.movilidad"), entrada("e5", "com.citypass.movilidad")), visibles)
    }
}
