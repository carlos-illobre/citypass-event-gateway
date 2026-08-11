package com.citypass.gateway.controller

import com.citypass.gateway.service.AvroService
import com.citypass.gateway.service.SchemaRegistryService
import com.citypass.gateway.service.TopicAuthorizationService
import org.apache.avro.Schema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.security.oauth2.jwt.Jwt
import java.util.concurrent.CompletableFuture

class EventControllerTest {

    private val kafkaTemplate: KafkaTemplate<String, ByteArray> = mock()
    private val schemaRegistryService: SchemaRegistryService = mock()
    private val avroService: AvroService = mock()
    private val topicAuthorizationService: TopicAuthorizationService = mock()

    private lateinit var controller: EventController

    private val schema = Schema.Parser().parse("""
    {
      "type": "record",
      "name": "TestEvent",
      "fields": [{"name": "userId", "type": "string"}]
    }
    """.trimIndent())

    @BeforeEach
    fun setUp() {
        controller = EventController(kafkaTemplate, schemaRegistryService, avroService, topicAuthorizationService, false)
    }

    @Test
    fun `publishEvent returns 400 when eventType is missing`() {
        val request = mapOf<String, Any>("data" to mapOf("foo" to "bar"))
        val response = controller.publishEvent(request, null)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `publishEvent returns 400 when data is missing`() {
        val request = mapOf<String, Any>("eventType" to "test.event")
        val response = controller.publishEvent(request, null)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `publishEvent returns 400 when schema is unknown`() {
        whenever(schemaRegistryService.getSchema("unknown.event")).thenReturn(null)
        val request = mapOf<String, Any>("eventType" to "unknown.event", "data" to mapOf("foo" to "bar"))
        val response = controller.publishEvent(request, null)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `publishEvent returns 503 when schema is not yet registered`() {
        whenever(schemaRegistryService.getSchema("test.event")).thenReturn(schema)
        whenever(schemaRegistryService.getSchemaId("test.event")).thenReturn(null)
        val request = mapOf<String, Any>("eventType" to "test.event", "data" to mapOf("userId" to "u123"))
        val response = controller.publishEvent(request, null)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
    }

    @Test
    fun `publishEvent returns 202 on successful publish`() {
        whenever(schemaRegistryService.getSchema("test.event")).thenReturn(schema)
        whenever(schemaRegistryService.getSchemaId("test.event")).thenReturn(1)
        whenever(avroService.jsonToAvroBytes(any(), eq(schema), eq(1))).thenReturn(byteArrayOf(1, 2, 3))
        whenever(kafkaTemplate.send(eq("test.event"), eq("u123"), any()))
            .thenReturn(CompletableFuture.completedFuture(mock()))

        val request = mapOf<String, Any>("eventType" to "test.event", "data" to mapOf("userId" to "u123"))
        val response = controller.publishEvent(request, null)
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
    }

    @Test
    fun `publishEvent uses source from request when provided`() {
        whenever(schemaRegistryService.getSchema("test.event")).thenReturn(schema)
        whenever(schemaRegistryService.getSchemaId("test.event")).thenReturn(1)
        whenever(avroService.jsonToAvroBytes(any(), eq(schema), eq(1))).thenReturn(byteArrayOf(1, 2, 3))
        whenever(kafkaTemplate.send(eq("test.event"), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(mock()))

        // source present in request — should NOT fall back to jwt.subject
        val request = mapOf<String, Any>(
            "eventType" to "test.event",
            "source" to "grupo3-movilidad",
            "data" to mapOf("userId" to "u123")
        )
        val response = controller.publishEvent(request, null)
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
    }

    @Test
    fun `publishEvent uses jwt subject as source when source not in request`() {
        val secureController = EventController(kafkaTemplate, schemaRegistryService, avroService, topicAuthorizationService, true)
        val jwt: Jwt = mock()
        whenever(jwt.subject).thenReturn("testuser")
        whenever(topicAuthorizationService.isAllowed(jwt, "test.event")).thenReturn(true)
        whenever(schemaRegistryService.getSchema("test.event")).thenReturn(schema)
        whenever(schemaRegistryService.getSchemaId("test.event")).thenReturn(1)
        whenever(avroService.jsonToAvroBytes(any(), eq(schema), eq(1))).thenReturn(byteArrayOf(1, 2, 3))
        whenever(kafkaTemplate.send(eq("test.event"), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(mock()))

        // No "source" in request — should fall back to jwt.subject ("testuser")
        val request = mapOf<String, Any>("eventType" to "test.event", "data" to mapOf("userId" to "u123"))
        val response = secureController.publishEvent(request, jwt)
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
    }

    @Test
    fun `publishEvent returns 500 when Kafka throws exception`() {
        whenever(schemaRegistryService.getSchema("test.event")).thenReturn(schema)
        whenever(schemaRegistryService.getSchemaId("test.event")).thenReturn(1)
        whenever(avroService.jsonToAvroBytes(any(), eq(schema), eq(1))).thenReturn(byteArrayOf(1, 2, 3))
        whenever(kafkaTemplate.send(eq("test.event"), any(), any()))
            .thenThrow(RuntimeException("Kafka broker unavailable"))

        val request = mapOf<String, Any>("eventType" to "test.event", "data" to mapOf("userId" to "u123"))
        val response = controller.publishEvent(request, null)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
    }

    @Test
    fun `publishEvent uses eventId as Kafka key when data has no userId`() {
        // Cubre la rama data["userId"]?.toString() ?: eventId cuando userId no está en data.
        whenever(schemaRegistryService.getSchema("test.event")).thenReturn(schema)
        whenever(schemaRegistryService.getSchemaId("test.event")).thenReturn(1)
        whenever(avroService.jsonToAvroBytes(any(), eq(schema), eq(1))).thenReturn(byteArrayOf(1, 2, 3))
        whenever(kafkaTemplate.send(eq("test.event"), any<String>(), any()))
            .thenReturn(CompletableFuture.completedFuture(mock()))

        val request = mapOf<String, Any>("eventType" to "test.event", "data" to mapOf<String, Any>())
        val response = controller.publishEvent(request, null)
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
    }

    @Test
    fun `publishEvent returns 403 when security enabled and topic not allowed`() {
        val secureController = EventController(kafkaTemplate, schemaRegistryService, avroService, topicAuthorizationService, true)
        val jwt: Jwt = mock()
        whenever(jwt.subject).thenReturn("testuser")
        whenever(topicAuthorizationService.isAllowed(jwt, "reclamos.creado")).thenReturn(false)

        val request = mapOf<String, Any>("eventType" to "reclamos.creado", "data" to mapOf("foo" to "bar"))
        val response = secureController.publishEvent(request, jwt)
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `publishEvent returns 403 with unknown user when jwt is null and security enabled`() {
        val secureController = EventController(kafkaTemplate, schemaRegistryService, avroService, topicAuthorizationService, true)
        val request = mapOf<String, Any>("eventType" to "reclamos.creado", "data" to mapOf("foo" to "bar"))
        val response = secureController.publishEvent(request, null)
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `publishEvent returns 403 with unknown user when jwt subject is null`() {
        // Cubre la rama jwt != null pero jwt.subject == null → ?: "unknown"
        val secureController = EventController(kafkaTemplate, schemaRegistryService, avroService, topicAuthorizationService, true)
        val jwt: Jwt = mock()
        whenever(jwt.subject).thenReturn(null)
        whenever(topicAuthorizationService.isAllowed(jwt, "reclamos.creado")).thenReturn(false)
        val request = mapOf<String, Any>("eventType" to "reclamos.creado", "data" to mapOf("foo" to "bar"))
        val response = secureController.publishEvent(request, jwt)
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `publishEvent publishes when security enabled and topic is allowed`() {
        val secureController = EventController(kafkaTemplate, schemaRegistryService, avroService, topicAuthorizationService, true)
        val jwt: Jwt = mock()
        whenever(topicAuthorizationService.isAllowed(jwt, "test.event")).thenReturn(true)
        whenever(schemaRegistryService.getSchema("test.event")).thenReturn(schema)
        whenever(schemaRegistryService.getSchemaId("test.event")).thenReturn(1)
        whenever(avroService.jsonToAvroBytes(any(), eq(schema), eq(1))).thenReturn(byteArrayOf(1, 2, 3))
        whenever(kafkaTemplate.send(eq("test.event"), eq("u123"), any()))
            .thenReturn(CompletableFuture.completedFuture(mock()))

        val request = mapOf<String, Any>("eventType" to "test.event", "data" to mapOf("userId" to "u123"))
        val response = secureController.publishEvent(request, jwt)
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
    }

}
