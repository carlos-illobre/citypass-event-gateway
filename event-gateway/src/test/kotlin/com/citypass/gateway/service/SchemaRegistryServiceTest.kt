package com.citypass.gateway.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SchemaRegistryServiceTest {

    @TempDir
    lateinit var tempSchemasDir: File

    private lateinit var schemaRegistryService: SchemaRegistryService

    private val validSchemaJson = """
    {
      "type": "record",
      "name": "TestEvent",
      "namespace": "com.citypass.test.events",
      "fields": [
        {"name": "eventId", "type": "string"},
        {"name": "eventType", "type": "string"},
        {"name": "timestamp", "type": "string"},
        {"name": "source", "type": "string"},
        {"name": "payload", "type": "string"}
      ]
    }
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        val sampleSchemaFile = File(tempSchemasDir, "movilidad.bici.devuelta.avsc")
        sampleSchemaFile.writeText("""
        {
          "type": "record",
          "name": "BiciDevuelta",
          "namespace": "com.citypass.movilidad.events",
          "fields": [
            {"name": "eventId", "type": "string"},
            {"name": "eventType", "type": "string"},
            {"name": "timestamp", "type": "string"},
            {"name": "source", "type": "string"},
            {"name": "biciId", "type": "string"}
          ]
        }
        """.trimIndent())

        schemaRegistryService = SchemaRegistryService(
            schemasDir = tempSchemasDir.absolutePath,
            schemaRegistryUrl = "http://localhost:8081"
        )
    }

    @Test
    fun `loadSchemas loads schema files from schemasDir`() {
        schemaRegistryService.loadSchemas()

        val availableTypes = schemaRegistryService.getAvailableEventTypes()
        assertTrue(availableTypes.contains("movilidad.bici.devuelta"))

        val schema = schemaRegistryService.getSchema("movilidad.bici.devuelta")
        assertNotNull(schema)
        assertEquals("BiciDevuelta", schema!!.name)
    }

    @Test
    fun `getSchema returns null for unknown event type`() {
        schemaRegistryService.loadSchemas()
        assertNull(schemaRegistryService.getSchema("non.existent.event"))
    }

    @Test
    fun `loadSchemas handles non-existing directory gracefully`() {
        val nonExistingService = SchemaRegistryService(
            schemasDir = "/path/to/non/existing/dir",
            schemaRegistryUrl = "http://localhost:8081"
        )
        nonExistingService.loadSchemas()
        assertTrue(nonExistingService.getAvailableEventTypes().isEmpty())
    }

    // --- Validaciones de registerNewSchema ---

    @Test
    fun `registerNewSchema fails when eventType format is invalid`() {
        val result = schemaRegistryService.registerNewSchema("InvalidFormat", validSchemaJson)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("dominio.entidad.accion"))
    }

    @Test
    fun `registerNewSchema fails when eventType has uppercase`() {
        val result = schemaRegistryService.registerNewSchema("Reclamos.Creado", validSchemaJson)
        assertTrue(result.isFailure)
    }

    @Test
    fun `registerNewSchema fails when schema json is invalid`() {
        val result = schemaRegistryService.registerNewSchema("test.evento", "not a valid json schema")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("inválido"))
    }

    @Test
    fun `registerNewSchema fails when schema is not a record type`() {
        val arraySchema = """{"type": "array", "items": "string"}"""
        val result = schemaRegistryService.registerNewSchema("test.evento", arraySchema)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("record"))
    }

    @Test
    fun `registerNewSchema fails when base fields are missing`() {
        val schemaWithoutBaseFields = """
        {
          "type": "record",
          "name": "Incomplete",
          "namespace": "com.citypass.test",
          "fields": [{"name": "someField", "type": "string"}]
        }
        """.trimIndent()
        val result = schemaRegistryService.registerNewSchema("test.evento", schemaWithoutBaseFields)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("campos base obligatorios"))
    }

    @Test
    fun `registerNewSchema fails when base field has wrong type`() {
        val schemaWithIntEventId = """
        {
          "type": "record",
          "name": "WrongType",
          "namespace": "com.citypass.test",
          "fields": [
            {"name": "eventId", "type": "int"},
            {"name": "eventType", "type": "string"},
            {"name": "timestamp", "type": "string"},
            {"name": "source", "type": "string"}
          ]
        }
        """.trimIndent()
        val result = schemaRegistryService.registerNewSchema("test.evento", schemaWithIntEventId)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("eventId"))
    }

    @Test
    fun `registerNewSchema fails when eventType already exists`() {
        schemaRegistryService.loadSchemas()
        val result = schemaRegistryService.registerNewSchema("movilidad.bici.devuelta", validSchemaJson)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Ya existe"))
    }

    // --- deleteSchema ---

    @Test
    fun `deleteSchema removes schema from memory and disk`() {
        schemaRegistryService.loadSchemas()
        assertTrue(schemaRegistryService.getAvailableEventTypes().contains("movilidad.bici.devuelta"))

        val deleted = schemaRegistryService.deleteSchema("movilidad.bici.devuelta")

        assertTrue(deleted)
        assertFalse(schemaRegistryService.getAvailableEventTypes().contains("movilidad.bici.devuelta"))
        assertFalse(File(tempSchemasDir, "movilidad.bici.devuelta.avsc").exists())
    }

    @Test
    fun `deleteSchema returns false when eventType does not exist`() {
        schemaRegistryService.loadSchemas()
        val deleted = schemaRegistryService.deleteSchema("non.existent.event")
        assertFalse(deleted)
    }
}
