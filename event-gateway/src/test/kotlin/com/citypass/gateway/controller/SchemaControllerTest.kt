package com.citypass.gateway.controller

import com.citypass.gateway.service.SchemaRegistryService
import org.apache.avro.Schema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt

class SchemaControllerTest {

    private val schemaRegistryService: SchemaRegistryService = mock()
    private lateinit var controller: SchemaController

    private val schema = Schema.Parser().parse("""
    {
      "type": "record",
      "name": "TestEvent",
      "namespace": "com.citypass.test",
      "fields": [{"name": "userId", "type": "string"}]
    }
    """.trimIndent())

    private val validRequest = mapOf<String, Any>(
        "namespace" to "com.citypass.test",
        "name" to "TestEvent",
        "fields" to listOf(mapOf("name" to "userId", "type" to "string"))
    )

    @BeforeEach
    fun setUp() {
        controller = SchemaController(schemaRegistryService)
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    fun `list returns available event types`() {
        whenever(schemaRegistryService.getAvailableEventTypes()).thenReturn(setOf("com.citypass.a.A", "com.citypass.b.B"))
        val response = controller.list()
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    fun `get returns 404 when schema not found`() {
        whenever(schemaRegistryService.getSchema("com.citypass.test.Unknown")).thenReturn(null)
        val response = controller.get("com.citypass.test.Unknown")
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `get returns 200 with schema when found`() {
        whenever(schemaRegistryService.getSchema("com.citypass.test.TestEvent")).thenReturn(schema)
        val response = controller.get("com.citypass.test.TestEvent")
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    // ── register — validaciones de campos ────────────────────────────────────

    @Test
    fun `register returns 400 when name is missing`() {
        val request = mapOf<String, Any>("namespace" to "com.citypass.test", "fields" to emptyList<Any>())
        val response = controller.register(request, null)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `register returns 400 when fields is missing`() {
        val request = mapOf<String, Any>("namespace" to "com.citypass.test", "name" to "TestEvent")
        val response = controller.register(request, null)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `register returns 400 when namespace is missing and no jwt`() {
        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, null)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    // ── register — fuentes de namespace ──────────────────────────────────────

    @Test
    fun `register reads namespace from request when jwt is null`() {
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any())).thenReturn(Result.success(1))
        val response = controller.register(validRequest, null)
        assertEquals(HttpStatus.CREATED, response.statusCode)
    }

    @Test
    fun `register reads namespace from jwt claim when jwt is present`() {
        val jwt: Jwt = mock()
        whenever(jwt.claims).thenReturn(mapOf("namespace" to "com.citypass.jwt"))
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any())).thenReturn(Result.success(2))

        val request = mapOf<String, Any>("name" to "JwtEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, jwt)
        assertEquals(HttpStatus.CREATED, response.statusCode)
    }

    @Test
    fun `register returns 400 when jwt present but namespace claim is missing`() {
        val jwt: Jwt = mock()
        whenever(jwt.claims).thenReturn(emptyMap())

        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, jwt)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    // ── register — resultados del servicio ───────────────────────────────────

    @Test
    fun `register returns 400 when validation fails with IllegalArgumentException`() {
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any()))
            .thenReturn(Result.failure(IllegalArgumentException("El name es inválido")))
        val response = controller.register(validRequest, null)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `register returns 500 when Schema Registry fails with RuntimeException`() {
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("Schema Registry unavailable")))
        val response = controller.register(validRequest, null)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
    }

    @Test
    fun `register returns 201 on success`() {
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any())).thenReturn(Result.success(5))
        val response = controller.register(validRequest, null)
        assertEquals(HttpStatus.CREATED, response.statusCode)
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    fun `delete returns 204 when schema found`() {
        whenever(schemaRegistryService.deleteSchema("com.citypass.test.TestEvent")).thenReturn(true)
        val response = controller.delete("com.citypass.test.TestEvent")
        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
    }

    @Test
    fun `delete returns 404 when schema not found`() {
        whenever(schemaRegistryService.deleteSchema("com.citypass.test.Unknown")).thenReturn(false)
        val response = controller.delete("com.citypass.test.Unknown")
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }
}
