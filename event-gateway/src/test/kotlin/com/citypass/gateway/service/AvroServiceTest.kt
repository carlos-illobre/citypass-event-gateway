package com.citypass.gateway.service

import org.apache.avro.Schema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.ByteBuffer

class AvroServiceTest {

    private val schemaRegistryService: SchemaRegistryService = mock()
    private val avroService = AvroService(schemaRegistryService)

    private val fullSchemaJson = """
    {
      "type": "record",
      "name": "TestEvent",
      "namespace": "com.citypass.test",
      "fields": [
        {"name": "strField", "type": "string"},
        {"name": "intField", "type": "int"},
        {"name": "longField", "type": "long"},
        {"name": "floatField", "type": "float"},
        {"name": "doubleField", "type": "double"},
        {"name": "boolField", "type": "boolean"}
      ]
    }
    """.trimIndent()
    private val fullSchema = Schema.Parser().parse(fullSchemaJson)

    private val simpleSchemaJson = """
    {
      "type": "record",
      "name": "SimpleEvent",
      "namespace": "com.citypass.test",
      "fields": [
        {"name": "eventId", "type": "string"},
        {"name": "count", "type": "int"}
      ]
    }
    """.trimIndent()
    private val simpleSchema = Schema.Parser().parse(simpleSchemaJson)

    // ── serialización ────────────────────────────────────────────────────────

    @Test
    fun `jsonToAvroBytes serializes map to Avro with Confluent header`() {
        val inputMap = mapOf<String, Any>(
            "strField" to "hello",
            "intField" to 42,
            "longField" to 100L,
            "floatField" to 3.14f,
            "doubleField" to 2.718,
            "boolField" to true
        )

        val resultBytes = avroService.jsonToAvroBytes(inputMap, fullSchema, 7)

        assertNotNull(resultBytes)
        val buffer = ByteBuffer.wrap(resultBytes)
        assertEquals(0x00.toByte(), buffer.get(), "Magic byte must be 0x00")
        assertEquals(7, buffer.getInt(), "Schema ID in header must match input schemaId")
    }

    @Test
    fun `jsonToAvroBytes handles bytes field via else branch`() {
        val bytesSchemaJson = """
        {
          "type": "record",
          "name": "BytesEvent",
          "namespace": "com.citypass.test",
          "fields": [{"name": "rawData", "type": "bytes"}]
        }
        """.trimIndent()
        val schema = Schema.Parser().parse(bytesSchemaJson)

        val resultBytes = avroService.jsonToAvroBytes(
            mapOf("rawData" to ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))),
            schema, 99
        )

        assertNotNull(resultBytes)
        val buffer = ByteBuffer.wrap(resultBytes)
        assertEquals(0x00.toByte(), buffer.get())
        assertEquals(99, buffer.getInt())
    }

    @Test
    fun `jsonToAvroBytes skips null values in map`() {
        val schemaWithOptional = Schema.Parser().parse("""
        {
          "type": "record",
          "name": "OptionalEvent",
          "namespace": "com.citypass.test",
          "fields": [
            {"name": "present", "type": "string"},
            {"name": "absent", "type": ["null", "string"], "default": null}
          ]
        }
        """.trimIndent())

        assertNotNull(avroService.jsonToAvroBytes(mapOf("present" to "value"), schemaWithOptional, 1))
    }

    // ── deserialización ──────────────────────────────────────────────────────

    @Test
    fun `deserialize successfully converts confluent avro bytes to map`() {
        val schemaId = 12
        whenever(schemaRegistryService.getSchemaById(schemaId)).thenReturn(simpleSchema)

        val bytes = avroService.jsonToAvroBytes(mapOf("eventId" to "evt-123", "count" to 5), simpleSchema, schemaId)
        val result = avroService.deserialize(bytes)

        assertEquals("evt-123", result["eventId"])
        assertEquals(5, result["count"])
    }

    @Test
    fun `deserialize throws when magic byte is invalid`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            avroService.deserialize(byteArrayOf(0x01, 0, 0, 0, 1, 10, 20))
        }
        assertTrue(ex.message!!.contains("bad magic byte"))
    }

    @Test
    fun `deserialize throws when schema is not found`() {
        val schemaId = 99
        whenever(schemaRegistryService.getSchemaById(schemaId)).thenReturn(null)

        val bytes = avroService.jsonToAvroBytes(mapOf("eventId" to "evt-123", "count" to 5), simpleSchema, schemaId)

        val ex = assertThrows(IllegalStateException::class.java) { avroService.deserialize(bytes) }
        assertTrue(ex.message!!.contains("No schema found for ID 99"))
    }
}
