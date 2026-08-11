package com.citypass.gateway.service

import org.apache.avro.Schema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.File

class SchemaRegistryServiceTest {

    @TempDir
    lateinit var tempSchemasDir: File

    private val registryUrl = "http://localhost:8081"
    private val testFqn = "com.citypass.movilidad.BiciDevuelta"
    private val testNamespace = "com.citypass.movilidad"
    private val testName = "BiciDevuelta"
    private val testFields = listOf(mapOf("name" to "biciId", "type" to "string"))

    private lateinit var builder: RestClient.Builder
    private lateinit var server: MockRestServiceServer
    private lateinit var schemaRegistryService: SchemaRegistryService

    @BeforeEach
    fun setUp() {
        val sampleFile = File(tempSchemasDir, "$testFqn.avsc")
        sampleFile.writeText("""
        {
          "type": "record",
          "name": "$testName",
          "namespace": "$testNamespace",
          "fields": [
            {"name": "eventId",   "type": "string"},
            {"name": "eventType", "type": "string"},
            {"name": "timestamp", "type": "string"},
            {"name": "source",    "type": "string"},
            {"name": "biciId",    "type": "string"}
          ]
        }
        """.trimIndent())

        builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        schemaRegistryService = SchemaRegistryService(
            restClient = builder.build(),
            schemasDir = tempSchemasDir.absolutePath,
            schemaRegistryUrl = registryUrl
        )
    }

    // ── loadSchemas ──────────────────────────────────────────────────────────

    @Test
    fun `loadSchemas indexes schema by FQN`() {
        schemaRegistryService.loadSchemas()

        val available = schemaRegistryService.getAvailableEventTypes()
        assertTrue(available.contains(testFqn))

        val schema = schemaRegistryService.getSchema(testFqn)
        assertNotNull(schema)
        assertEquals(testName, schema!!.name)
    }

    @Test
    fun `getSchema returns null for unknown FQN`() {
        schemaRegistryService.loadSchemas()
        assertNull(schemaRegistryService.getSchema("com.citypass.otros.Desconocido"))
    }

    @Test
    fun `loadSchemas handles non-existing directory gracefully`() {
        val nonExistingService = SchemaRegistryService(
            restClient = builder.build(),
            schemasDir = "/path/to/non/existing/dir",
            schemaRegistryUrl = registryUrl
        )
        nonExistingService.loadSchemas()
        assertTrue(nonExistingService.getAvailableEventTypes().isEmpty())
    }

    @Test
    fun `loadSchemas handles path that is a file not a directory`() {
        val filePathService = SchemaRegistryService(
            restClient = builder.build(),
            schemasDir = File(tempSchemasDir, "$testFqn.avsc").absolutePath,
            schemaRegistryUrl = registryUrl
        )
        filePathService.loadSchemas()
        assertTrue(filePathService.getAvailableEventTypes().isEmpty())
    }

    // ── registerSchemas ──────────────────────────────────────────────────────

    @Test
    fun `registerSchemas registers all schemas loaded from disk`() {
        schemaRegistryService.loadSchemas()

        server.expect(requestTo("$registryUrl/subjects/$testFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 1}""", MediaType.APPLICATION_JSON))

        schemaRegistryService.registerSchemas()

        assertEquals(1, schemaRegistryService.getSchemaId(testFqn))
        server.verify()
    }

    // ── registerWithRetry ────────────────────────────────────────────────────

    @Test
    fun `registerWithRetry stores schema ID on success`() {
        schemaRegistryService.loadSchemas()
        val schema = schemaRegistryService.getSchema(testFqn)!!

        server.expect(requestTo("$registryUrl/subjects/$testFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 42}""", MediaType.APPLICATION_JSON))

        schemaRegistryService.registerWithRetry(testFqn, schema, maxRetries = 1)

        assertEquals(42, schemaRegistryService.getSchemaId(testFqn))
        server.verify()
    }

    @Test
    fun `registerWithRetry does not store schema ID when all attempts fail`() {
        schemaRegistryService.loadSchemas()
        val schema = schemaRegistryService.getSchema(testFqn)!!

        server.expect(requestTo("$registryUrl/subjects/$testFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withServerError())

        schemaRegistryService.registerWithRetry(testFqn, schema, maxRetries = 1, retryDelayMs = 0)

        assertNull(schemaRegistryService.getSchemaId(testFqn))
        server.verify()
    }

    @Test
    fun `registerWithRetry succeeds on second attempt after first failure`() {
        schemaRegistryService.loadSchemas()
        val schema = schemaRegistryService.getSchema(testFqn)!!

        server.expect(requestTo("$registryUrl/subjects/$testFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withServerError())
        server.expect(requestTo("$registryUrl/subjects/$testFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 11}""", MediaType.APPLICATION_JSON))

        schemaRegistryService.registerWithRetry(testFqn, schema, maxRetries = 2, retryDelayMs = 0)

        assertEquals(11, schemaRegistryService.getSchemaId(testFqn))
        server.verify()
    }

    // ── registerNewSchema — validaciones ────────────────────────────────────

    @Test
    fun `registerNewSchema fails when name format is invalid`() {
        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "invalid name", emptyList())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("identificador Avro válido"))
    }

    @Test
    fun `registerNewSchema fails when name starts with digit`() {
        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "123Event", emptyList())
        assertTrue(result.isFailure)
    }

    @Test
    fun `registerNewSchema fails when namespace format is invalid`() {
        val result = schemaRegistryService.registerNewSchema("InvalidNamespace", "TestEvent", emptyList())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("namespace"))
    }

    @Test
    fun `registerNewSchema fails when namespace has uppercase`() {
        val result = schemaRegistryService.registerNewSchema("Com.Citypass.Test", "TestEvent", emptyList())
        assertTrue(result.isFailure)
    }

    @Test
    fun `registerNewSchema fails when user field conflicts with reserved field`() {
        val fields = listOf(mapOf("name" to "eventId", "type" to "string"))
        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "TestEvent", fields)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("eventId"))
    }

    @Test
    fun `registerNewSchema ignores non-map field element during name extraction`() {
        // Covers the null branch of (it as? Map<String, Any>) in mapNotNull
        val fields: List<Any> = listOf("not-a-map")
        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "BadField", fields)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("inválido"))
    }

    @Test
    fun `registerNewSchema ignores field map without name key during name extraction`() {
        // Covers the null branch of ?.get("name") in mapNotNull
        val fields: List<Any> = listOf(mapOf("type" to "string"))
        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "NoName", fields)
        assertTrue(result.isFailure)
    }

    @Test
    fun `registerNewSchema fails when Avro schema is invalid (bad field type)`() {
        val fields = listOf(mapOf("name" to "amount", "type" to "not-a-valid-avro-type"))
        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "TestEvent", fields)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("inválido"))
    }

    @Test
    fun `registerNewSchema fails when FQN already exists`() {
        schemaRegistryService.loadSchemas()
        val result = schemaRegistryService.registerNewSchema(testNamespace, testName, testFields)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Ya existe"))
    }

    @Test
    fun `registerNewSchema returns success and persists avsc file`() {
        val newFqn = "com.citypass.test.NuevoEvento"
        server.expect(requestTo("$registryUrl/subjects/$newFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 7}""", MediaType.APPLICATION_JSON))

        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "NuevoEvento", testFields)

        assertTrue(result.isSuccess)
        assertEquals(7, result.getOrThrow())
        assertTrue(File(tempSchemasDir, "$newFqn.avsc").exists())
        assertEquals(7, schemaRegistryService.getSchemaId(newFqn))
        server.verify()
    }

    @Test
    fun `registerNewSchema auto-injects base fields into schema`() {
        val newFqn = "com.citypass.test.BaseFieldCheck"
        server.expect(requestTo("$registryUrl/subjects/$newFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 8}""", MediaType.APPLICATION_JSON))

        schemaRegistryService.registerNewSchema("com.citypass.test", "BaseFieldCheck", emptyList())

        val schema = schemaRegistryService.getSchema(newFqn)
        assertNotNull(schema)
        val fieldNames = schema!!.fields.map { it.name() }
        assertTrue(fieldNames.containsAll(listOf("eventId", "eventType", "timestamp", "source")))
    }

    @Test
    fun `registerNewSchema supports nested record fields`() {
        val newFqn = "com.citypass.test.ConAnidado"
        server.expect(requestTo("$registryUrl/subjects/$newFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 9}""", MediaType.APPLICATION_JSON))

        val nestedField = mapOf(
            "name" to "ubicacion",
            "type" to mapOf(
                "type" to "record",
                "name" to "Coordenadas",
                "fields" to listOf(
                    mapOf("name" to "lat", "type" to "double"),
                    mapOf("name" to "lon", "type" to "double")
                )
            )
        )
        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "ConAnidado", listOf(nestedField))

        assertTrue(result.isSuccess)
        val schema = schemaRegistryService.getSchema(newFqn)
        assertNotNull(schema!!.getField("ubicacion"))
    }

    @Test
    fun `registerNewSchema returns failure when Schema Registry rejects`() {
        val newFqn = "com.citypass.test.SinRegistry"
        server.expect(requestTo("$registryUrl/subjects/$newFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withServerError())

        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "SinRegistry", testFields)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Failed to register schema"))
        assertNull(schemaRegistryService.getSchema(newFqn))
    }

    // ── deleteSchema ─────────────────────────────────────────────────────────

    @Test
    fun `deleteSchema removes schema from memory and disk`() {
        schemaRegistryService.loadSchemas()
        assertTrue(schemaRegistryService.getAvailableEventTypes().contains(testFqn))

        val deleted = schemaRegistryService.deleteSchema(testFqn)

        assertTrue(deleted)
        assertFalse(schemaRegistryService.getAvailableEventTypes().contains(testFqn))
        assertFalse(File(tempSchemasDir, "$testFqn.avsc").exists())
    }

    @Test
    fun `deleteSchema returns false when FQN does not exist`() {
        schemaRegistryService.loadSchemas()
        val deleted = schemaRegistryService.deleteSchema("com.citypass.otros.Inexistente")
        assertFalse(deleted)
    }

    @Test
    fun `deleteSchema works when avsc file has already been deleted from disk`() {
        schemaRegistryService.loadSchemas()
        File(tempSchemasDir, "$testFqn.avsc").delete()

        val deleted = schemaRegistryService.deleteSchema(testFqn)

        assertTrue(deleted)
        assertFalse(schemaRegistryService.getAvailableEventTypes().contains(testFqn))
    }

    @Test
    fun `deleteSchema removes reverse index entry when schema had a registered ID`() {
        val newFqn = "com.citypass.test.ParaBorrar"
        server.expect(requestTo("$registryUrl/subjects/$newFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 99}""", MediaType.APPLICATION_JSON))

        schemaRegistryService.registerNewSchema("com.citypass.test", "ParaBorrar", testFields)
        schemaRegistryService.deleteSchema(newFqn)

        assertNull(schemaRegistryService.getSchemaId(newFqn))
    }

    // ── getSchemaById ────────────────────────────────────────────────────────

    @Test
    fun `getSchemaById returns from local cache without HTTP call`() {
        val newFqn = "com.citypass.test.CacheTest"
        server.expect(requestTo("$registryUrl/subjects/$newFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 5}""", MediaType.APPLICATION_JSON))

        schemaRegistryService.registerNewSchema("com.citypass.test", "CacheTest", testFields)

        val schema = schemaRegistryService.getSchemaById(5)
        assertNotNull(schema)
        server.verify()
    }

    @Test
    fun `getSchemaById fetches from Schema Registry when not in cache`() {
        schemaRegistryService.loadSchemas()
        val schema = schemaRegistryService.getSchema(testFqn)!!
        val avroSchemaStr = schema.toString().replace("\"", "\\\"")

        server.expect(requestTo("$registryUrl/schemas/ids/10"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""{"schema": "$avroSchemaStr"}""", MediaType.APPLICATION_JSON))

        val fetched = schemaRegistryService.getSchemaById(10)

        assertNotNull(fetched)
        server.verify()
    }

    @Test
    fun `getSchemaById returns null when Schema Registry returns error`() {
        server.expect(requestTo("$registryUrl/schemas/ids/99"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withServerError())

        assertNull(schemaRegistryService.getSchemaById(99))
        server.verify()
    }

    @Test
    fun `getSchemaById returns null when response has no schema field`() {
        server.expect(requestTo("$registryUrl/schemas/ids/11"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""{"unexpectedField": "value"}""", MediaType.APPLICATION_JSON))

        assertNull(schemaRegistryService.getSchemaById(11))
        server.verify()
    }

    @Test
    fun `getSchemaById returns null when response body is null`() {
        server.expect(requestTo("$registryUrl/schemas/ids/55"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("null", MediaType.APPLICATION_JSON))

        assertNull(schemaRegistryService.getSchemaById(55))
        server.verify()
    }
}
