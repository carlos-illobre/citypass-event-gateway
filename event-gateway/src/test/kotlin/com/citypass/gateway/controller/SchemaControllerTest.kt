package com.citypass.gateway.controller

import com.citypass.gateway.service.SchemaRegistryService
import org.apache.avro.Schema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.jwt.Jwt

class SchemaControllerTest {

    private val schemaRegistryService: SchemaRegistryService = mock()
    private lateinit var controller: SchemaController

    private val fqn = "com.citypass.test.TestEvent"

    private val schema = Schema.Parser().parse("""
    {
      "type": "record",
      "name": "TestEvent",
      "namespace": "com.citypass.test",
      "doc": "Con acentos: publicación de una bicicleta liberada.",
      "fields": [{"name": "userId", "type": "string"}]
    }
    """.trimIndent())

    private fun jwtWithNamespace(namespace: String): Jwt {
        val jwt: Jwt = mock()
        whenever(jwt.claims).thenReturn(mapOf("namespace" to namespace))
        return jwt
    }

    private fun problemOf(response: ResponseEntity<Any>): ProblemDetail = response.body as ProblemDetail

    @BeforeEach
    fun setUp() {
        controller = SchemaController(schemaRegistryService)
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    fun `list returns the summaries from the service`() {
        val summaries = listOf(mapOf<String, Any?>("fqn" to fqn, "schemaId" to 3))
        whenever(schemaRegistryService.listEventTypes(null)).thenReturn(summaries)

        val response = controller.list(null)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(summaries, response.body)
    }

    @Test
    fun `list forwards the namespace filter`() {
        whenever(schemaRegistryService.listEventTypes("com.citypass.test")).thenReturn(emptyList())

        val response = controller.list("com.citypass.test")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(emptyList<Any>(), response.body)
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    fun `get returns 404 when the event type does not exist`() {
        whenever(schemaRegistryService.getSchema("com.citypass.test.Unknown")).thenReturn(null)

        val response = controller.get("com.citypass.test.Unknown")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("Event type no encontrado", problemOf(response).title)
    }

    @Test
    fun `get returns the schema as a JSON object, not a string`() {
        whenever(schemaRegistryService.getSchema(fqn)).thenReturn(schema)

        val response = controller.get(fqn)

        assertEquals(HttpStatus.OK, response.statusCode)
        // Devolverlo como String hacía que Spring lo escribiera como text/plain
        // en ISO-8859-1, rompiendo los acentos de los `doc`.
        val body = response.body as Map<*, *>
        assertEquals("TestEvent", body["name"])
        assertEquals("com.citypass.test", body["namespace"])
        assertTrue((body["doc"] as String).contains("publicación"))
    }

    // ── register ──────────────────────────────────────────────────────────────

    @Test
    fun `register returns 400 when name is missing`() {
        val response = controller.register(mapOf("fields" to emptyList<Any>()), null)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `register returns 401 when there is no JWT`() {
        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, null)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `register returns 400 when the JWT has no namespace claim`() {
        val jwt: Jwt = mock()
        whenever(jwt.claims).thenReturn(emptyMap())

        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, jwt)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Token sin namespace", problemOf(response).title)
    }

    @Test
    fun `register returns 400 when fields is missing`() {
        val jwt = jwtWithNamespace("com.citypass.test")
        val response = controller.register(mapOf("name" to "TestEvent"), jwt)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `register returns 201 with the FQN and a Location header`() {
        val jwt = jwtWithNamespace("com.citypass.test")
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any())).thenReturn(Result.success(5))

        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, jwt)

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertEquals("/api/v1/event-types/$fqn", response.headers.location.toString())

        val body = response.body as Map<*, *>
        assertEquals(fqn, body["fqn"])
        assertEquals("com.citypass.test", body["namespace"])
        assertEquals("TestEvent", body["name"])
        assertEquals(5, body["schemaId"])
    }

    @Test
    fun `register returns 400 when the event type is invalid`() {
        val jwt = jwtWithNamespace("com.citypass.test")
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any()))
            .thenReturn(Result.failure(IllegalArgumentException("El name es inválido")))

        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, jwt)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("El name es inválido", problemOf(response).detail)
    }

    @Test
    fun `register returns 502 when the Schema Registry fails`() {
        val jwt = jwtWithNamespace("com.citypass.test")
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("Schema Registry unavailable")))

        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, jwt)

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        assertEquals("Error del Schema Registry", problemOf(response).title)
    }

    @Test
    fun `register tolerates a validation failure without a message`() {
        val jwt = jwtWithNamespace("com.citypass.test")
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any()))
            .thenReturn(Result.failure(IllegalArgumentException()))

        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, jwt)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("", problemOf(response).detail)
    }

    @Test
    fun `register tolerates a registry failure without a message`() {
        val jwt = jwtWithNamespace("com.citypass.test")
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException()))

        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, jwt)

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        assertEquals("", problemOf(response).detail)
    }

    // ── archivar ──────────────────────────────────────────────────────────────

    @Test
    fun `archives the event type without deleting anything`() {
        whenever(schemaRegistryService.archiveEventType(fqn)).thenReturn(Result.success(Unit))

        val response = controller.updateStatus(fqn, mapOf("status" to "archived"))

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body as Map<*, *>
        assertEquals(fqn, body["fqn"])
        assertEquals("archived", body["status"])
    }

    @Test
    fun `returns 400 when status is missing`() {
        val response = controller.updateStatus(fqn, emptyMap())

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Falta un campo requerido", problemOf(response).title)
    }

    @Test
    fun `rejects reactivating, which is not implemented yet`() {
        val response = controller.updateStatus(fqn, mapOf("status" to "active"))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Estado no soportado", problemOf(response).title)
        assertTrue(problemOf(response).detail!!.contains("no está implementado"))
    }

    @Test
    fun `returns 404 when the event type does not exist`() {
        whenever(schemaRegistryService.archiveEventType("com.citypass.test.Unknown"))
            .thenReturn(Result.failure(NoSuchElementException("No existe")))

        val response = controller.updateStatus("com.citypass.test.Unknown", mapOf("status" to "archived"))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("No existe", problemOf(response).detail)
    }

    @Test
    fun `tolerates a not-found failure without a message`() {
        whenever(schemaRegistryService.archiveEventType(fqn))
            .thenReturn(Result.failure(NoSuchElementException()))

        val response = controller.updateStatus(fqn, mapOf("status" to "archived"))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("", problemOf(response).detail)
    }
}
