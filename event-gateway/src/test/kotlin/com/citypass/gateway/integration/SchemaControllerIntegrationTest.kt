package com.citypass.gateway.integration

import com.citypass.gateway.controller.EventMetadataController
import com.citypass.gateway.controller.SchemaController
import com.citypass.gateway.service.SchemaRegistryService
import org.apache.avro.Schema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

@Tag("integration")
class SchemaControllerIntegrationTest {

    private val schemaRegistryService: SchemaRegistryService = mock()
    private lateinit var mockMvc: MockMvc

    private val fqn = "com.citypass.movilidad.BicicletaLiberada"

    private val schema = Schema.Parser().parse("""
    {
      "type": "record", "name": "BicicletaLiberada", "namespace": "com.citypass.movilidad",
      "doc": "Publicación de una bicicleta liberada.",
      "fields": [{"name": "nroSerie", "type": "string"}]
    }
    """.trimIndent())

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(
                SchemaController(schemaRegistryService),
                EventMetadataController(schemaRegistryService)
            )
            .build()
    }

    @Test
    fun `GET event-types returns a bare array of summaries`() {
        whenever(schemaRegistryService.listEventTypes(null)).thenReturn(
            listOf(mapOf(
                "fqn" to fqn,
                "namespace" to "com.citypass.movilidad",
                "name" to "BicicletaLiberada",
                "schemaId" to 17
            ))
        )

        mockMvc.perform(get("/api/v1/event-types"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$[0].fqn").value(fqn))
            .andExpect(jsonPath("$[0].schemaId").value(17))
    }

    @Test
    fun `GET event-types forwards the namespace filter`() {
        whenever(schemaRegistryService.listEventTypes("com.citypass.movilidad")).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/event-types").param("namespace", "com.citypass.movilidad"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isEmpty)
    }

    @Test
    fun `GET a schema serves application-json with UTF-8, not text-plain`() {
        whenever(schemaRegistryService.getSchema(fqn)).thenReturn(schema)

        mockMvc.perform(get("/api/v1/event-types/$fqn"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            // Con text/plain;charset=ISO-8859-1 este acento salía corrupto.
            .andExpect(jsonPath("$.doc").value("Publicación de una bicicleta liberada."))
            .andExpect(jsonPath("$.name").value("BicicletaLiberada"))
    }

    @Test
    fun `GET an unknown schema returns problem+json`() {
        whenever(schemaRegistryService.getSchema("com.citypass.otros.Nada")).thenReturn(null)

        mockMvc.perform(get("/api/v1/event-types/com.citypass.otros.Nada"))
            .andExpect(status().isNotFound)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Event type no encontrado"))
    }

    @Test
    fun `GET event-metadata is its own resource, not a sub-path of event-types`() {
        val metadata = Schema.Parser().parse("""
        {
          "type": "record", "name": "EventMetadata", "namespace": "com.citypass.gateway",
          "fields": [{"name": "eventId", "type": "string"}]
        }
        """.trimIndent())
        whenever(schemaRegistryService.getMetadataSchema()).thenReturn(metadata)

        mockMvc.perform(get("/api/v1/event-metadata"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.name").value("EventMetadata"))
    }
}
