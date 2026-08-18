package com.citypass.gateway.controller

import com.citypass.gateway.service.SchemaRegistryService
import org.apache.avro.Schema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus

class EventMetadataControllerTest {

    private val schemaRegistryService: SchemaRegistryService = mock()
    private val controller = EventMetadataController(schemaRegistryService)

    private val metadata = Schema.Parser().parse("""
    {
      "type": "record", "name": "EventMetadata", "namespace": "com.citypass.gateway",
      "doc": "Metadata con acentos: identificación del emisor.",
      "fields": [
        {"name": "eventId", "type": "string"},
        {"name": "payloadHash", "type": "string"}
      ]
    }
    """.trimIndent())

    @Test
    fun `returns the EventMetadata record as a JSON object`() {
        whenever(schemaRegistryService.getMetadataSchema()).thenReturn(metadata)

        val response = controller.get()

        assertEquals(HttpStatus.OK, response.statusCode)

        val body = response.body as Map<*, *>
        assertEquals("EventMetadata", body["name"])
        assertEquals("com.citypass.gateway", body["namespace"])

        val fields = body["fields"] as List<*>
        assertEquals(
            listOf("eventId", "payloadHash"),
            fields.map { (it as Map<*, *>)["name"] }
        )

        // Devolverlo como objeto y no como String evita el text/plain en ISO-8859-1
        // que corrompía los acentos de los `doc`.
        assertTrue((body["doc"] as String).contains("identificación"))
    }
}
