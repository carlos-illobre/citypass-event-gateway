package com.citypass.gateway.service

import org.apache.avro.Schema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class AvroServiceTest {

    private val avroService = AvroService()

    private val schemaJson = """
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

    private val schema = Schema.Parser().parse(schemaJson)

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
        val schemaId = 7

        val resultBytes = avroService.jsonToAvroBytes(inputMap, schema, schemaId)

        assertNotNull(resultBytes)
        val buffer = ByteBuffer.wrap(resultBytes)
        assertEquals(0x00.toByte(), buffer.get(), "Magic byte must be 0x00")
        assertEquals(7, buffer.getInt(), "Schema ID in header must match input schemaId")
    }
}
