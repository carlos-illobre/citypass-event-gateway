package com.citypass.gateway.service

import org.apache.avro.Schema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AvroDeserializerServiceTest {

    private val schemaRegistryService: SchemaRegistryService = mock()
    private val avroDeserializerService = AvroDeserializerService(schemaRegistryService)
    private val avroService = AvroService()

    private val schemaJson = """
    {
      "type": "record",
      "name": "TestEvent",
      "namespace": "com.citypass.test",
      "fields": [
        {"name": "eventId", "type": "string"},
        {"name": "count", "type": "int"}
      ]
    }
    """.trimIndent()
    private val schema = Schema.Parser().parse(schemaJson)

    @Test
    fun `deserialize successfully converts confluent avro bytes to map`() {
        val schemaId = 12
        whenever(schemaRegistryService.getSchemaById(schemaId)).thenReturn(schema)

        val inputMap = mapOf("eventId" to "evt-123", "count" to 5)
        val bytes = avroService.jsonToAvroBytes(inputMap, schema, schemaId)

        val result = avroDeserializerService.deserialize(bytes)

        assertEquals("evt-123", result["eventId"])
        assertEquals(5, result["count"])
    }

    @Test
    fun `deserialize throws IllegalStateException when magic byte is invalid`() {
        val invalidBytes = byteArrayOf(0x01, 0, 0, 0, 1, 10, 20)

        val exception = assertThrows(IllegalStateException::class.java) {
            avroDeserializerService.deserialize(invalidBytes)
        }
        assertTrue(exception.message!!.contains("bad magic byte"))
    }

    @Test
    fun `deserialize throws IllegalStateException when schema is missing`() {
        val schemaId = 99
        whenever(schemaRegistryService.getSchemaById(schemaId)).thenReturn(null)

        val inputMap = mapOf("eventId" to "evt-123", "count" to 5)
        val bytes = avroService.jsonToAvroBytes(inputMap, schema, schemaId)

        val exception = assertThrows(IllegalStateException::class.java) {
            avroDeserializerService.deserialize(bytes)
        }
        assertTrue(exception.message!!.contains("No schema found for ID 99"))
    }
}
