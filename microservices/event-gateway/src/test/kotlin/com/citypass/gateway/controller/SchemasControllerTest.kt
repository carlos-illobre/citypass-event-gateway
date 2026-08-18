package com.citypass.gateway.controller

import com.citypass.gateway.service.SchemaRegistryService
import org.apache.avro.Schema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail

class SchemasControllerTest {

    private val schemaRegistryService: SchemaRegistryService = mock()
    private val controller = SchemasController(schemaRegistryService)

    private val schema = Schema.Parser().parse("""
    {
      "type": "record", "name": "BicicletaLiberada", "namespace": "com.citypass.movilidad",
      "fields": [{"name": "nroSerie", "type": "string"}]
    }
    """.trimIndent())

    @Test
    fun `returns the schema in the Confluent registry format`() {
        whenever(schemaRegistryService.getSchemaById(17)).thenReturn(schema)

        val response = controller.byId(17)

        assertEquals(HttpStatus.OK, response.statusCode)
        // Los deserializadores estándar esperan el schema como cadena bajo "schema",
        // no como objeto: la compatibilidad depende de respetar esa forma.
        val body = response.body as Map<*, *>
        val texto = body["schema"] as String
        assertEquals("com.citypass.movilidad.BicicletaLiberada", Schema.Parser().parse(texto).fullName)
    }

    @Test
    fun `returns 404 when there is no schema with that ID`() {
        whenever(schemaRegistryService.getSchemaById(99)).thenReturn(null)

        val response = controller.byId(99)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("Schema no encontrado", (response.body as ProblemDetail).title)
    }
}
