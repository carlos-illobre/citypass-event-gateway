package com.citypass.gateway.controller

import com.citypass.gateway.service.AvroService
import com.citypass.gateway.service.SchemaRegistryService
import com.citypass.gateway.service.TopicAuthorizationService
import org.apache.avro.Schema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.security.oauth2.jwt.Jwt
import java.util.concurrent.CompletableFuture

class EventControllerTest {

    private val kafkaTemplate: KafkaTemplate<String, ByteArray> = mock()
    private val schemaRegistryService: SchemaRegistryService = mock()
    private val avroService: AvroService = mock()
    private val topicAuthorizationService: TopicAuthorizationService = mock()

    private lateinit var eventController: EventController

    private val schemaJson = """
    {
      "type": "record",
      "name": "TestEvent",
      "fields": [{"name": "userId", "type": "string"}]
    }
    """.trimIndent()
    private val schema = Schema.Parser().parse(schemaJson)

    @BeforeEach
    fun setUp() {
        // securityEnabled = false por defecto en tests unitarios
        eventController = EventController(kafkaTemplate, schemaRegistryService, avroService, topicAuthorizationService, false)
    }

    @Test
    fun `publishEvent returns 400 when eventType is missing`() {
        val request = mapOf<String, Any>("data" to mapOf("foo" to "bar"))
        val response = eventController.publishEvent(request, null)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `publishEvent returns 400 when data is missing`() {
        val request = mapOf<String, Any>("eventType" to "test.event")
        val response = eventController.publishEvent(request, null)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `publishEvent returns 400 when schema is unknown`() {
        whenever(schemaRegistryService.getSchema("unknown.event")).thenReturn(null)
        val request = mapOf<String, Any>("eventType" to "unknown.event", "data" to mapOf("foo" to "bar"))
        val response = eventController.publishEvent(request, null)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `publishEvent returns 503 when schema is not yet registered`() {
        whenever(schemaRegistryService.getSchema("test.event")).thenReturn(schema)
        whenever(schemaRegistryService.getSchemaId("test.event")).thenReturn(null)
        val request = mapOf<String, Any>("eventType" to "test.event", "data" to mapOf("userId" to "u123"))
        val response = eventController.publishEvent(request, null)
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
        val response = eventController.publishEvent(request, null)
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
    }

    @Test
    fun `publishEvent returns 403 when security enabled and topic not allowed`() {
        val controller = EventController(kafkaTemplate, schemaRegistryService, avroService, topicAuthorizationService, true)
        val jwt: Jwt = mock()
        whenever(jwt.claims).thenReturn(mapOf("grupo" to "grupo3"))
        whenever(topicAuthorizationService.isAllowed(jwt, "reclamos.creado")).thenReturn(false)

        val request = mapOf<String, Any>("eventType" to "reclamos.creado", "data" to mapOf("foo" to "bar"))
        val response = controller.publishEvent(request, jwt)
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `publishEvent publishes when security enabled and topic is allowed`() {
        val controller = EventController(kafkaTemplate, schemaRegistryService, avroService, topicAuthorizationService, true)
        val jwt: Jwt = mock()
        whenever(topicAuthorizationService.isAllowed(jwt, "test.event")).thenReturn(true)
        whenever(schemaRegistryService.getSchema("test.event")).thenReturn(schema)
        whenever(schemaRegistryService.getSchemaId("test.event")).thenReturn(1)
        whenever(avroService.jsonToAvroBytes(any(), eq(schema), eq(1))).thenReturn(byteArrayOf(1, 2, 3))
        whenever(kafkaTemplate.send(eq("test.event"), eq("u123"), any()))
            .thenReturn(CompletableFuture.completedFuture(mock()))

        val request = mapOf<String, Any>("eventType" to "test.event", "data" to mapOf("userId" to "u123"))
        val response = controller.publishEvent(request, jwt)
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
    }

    @Test
    fun `listSchemas returns available event types`() {
        whenever(schemaRegistryService.getAvailableEventTypes()).thenReturn(setOf("type.a", "type.b"))
        val response = eventController.listSchemas()
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `getSchema returns 404 when schema not found`() {
        whenever(schemaRegistryService.getSchema("unknown")).thenReturn(null)
        val response = eventController.getSchema("unknown")
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `getSchema returns 200 with schema when found`() {
        whenever(schemaRegistryService.getSchema("test.event")).thenReturn(schema)
        val response = eventController.getSchema("test.event")
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `health returns status UP`() {
        val response = eventController.health()
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `registerSchema returns 400 when eventType is missing`() {
        val request = mapOf<String, Any>("schema" to mapOf("type" to "record"))
        val response = eventController.registerSchema(request)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `registerSchema returns 400 when schema is missing`() {
        val request = mapOf<String, Any>("eventType" to "test.event")
        val response = eventController.registerSchema(request)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `registerSchema returns 400 when validation fails`() {
        whenever(schemaRegistryService.registerNewSchema(any(), any()))
            .thenReturn(Result.failure(IllegalArgumentException("El eventType debe seguir el formato...")))
        val request = mapOf<String, Any>(
            "eventType" to "INVALID_FORMAT",
            "schema" to mapOf("type" to "record", "name" to "Test", "fields" to emptyList<Any>())
        )
        val response = eventController.registerSchema(request)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `registerSchema returns 201 on success`() {
        whenever(schemaRegistryService.registerNewSchema(any(), any())).thenReturn(Result.success(5))
        val request = mapOf<String, Any>(
            "eventType" to "test.creado",
            "schema" to mapOf("type" to "record", "name" to "Test", "fields" to emptyList<Any>())
        )
        val response = eventController.registerSchema(request)
        assertEquals(HttpStatus.CREATED, response.statusCode)
    }

    @Test
    fun `deleteSchema returns 204 when schema found`() {
        whenever(schemaRegistryService.deleteSchema("test.event")).thenReturn(true)
        val response = eventController.deleteSchema("test.event")
        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
    }

    @Test
    fun `deleteSchema returns 404 when schema not found`() {
        whenever(schemaRegistryService.deleteSchema("unknown.event")).thenReturn(false)
        val response = eventController.deleteSchema("unknown.event")
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }
}
