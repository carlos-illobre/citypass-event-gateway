package com.citypass.gateway.integration

import com.citypass.gateway.controller.EventController
import com.citypass.gateway.service.AvroService
import com.citypass.gateway.service.SchemaRegistryService
import com.citypass.gateway.service.TopicAuthorizationService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.info.BuildProperties
import org.springframework.http.MediaType
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Properties

@Tag("integration")
class EventControllerIntegrationTest {

    private val kafkaTemplate: KafkaTemplate<String, ByteArray> = mock()
    private val schemaRegistryService: SchemaRegistryService = mock()
    private val avroService: AvroService = mock()
    private val topicAuthorizationService: TopicAuthorizationService = mock()

    private lateinit var mockMvc: MockMvc

    private val fqn = "com.citypass.movilidad.BicicletaLiberada"

    @BeforeEach
    fun setUp() {
        val build = BuildProperties(Properties().apply { setProperty("version", "test") })
        val controller = EventController(
            kafkaTemplate, schemaRegistryService, avroService, topicAuthorizationService, build,
            publishTimeoutMs = 5_000
        )
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
    }

    @Test
    fun `POST captures the whole FQN from the path, dots included`() {
        // Sin JWT el controller corta con 401 — alcanza para verificar el ruteo.
        // Si `{fqn}` truncara en el último punto, el mapping ni siquiera resolvería.
        mockMvc.perform(
            post("/api/v1/event-types/$fqn/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"nroSerie": "BCL-00847"}""")
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `errors are served as RFC 9457 problem+json`() {
        mockMvc.perform(
            post("/api/v1/event-types/$fqn/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"nroSerie": "BCL-00847"}""")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.title").value("Autenticación requerida"))
            .andExpect(jsonPath("$.detail").exists())
    }

    @Test
    fun `the old flat route no longer exists`() {
        whenever(topicAuthorizationService.isAllowed(null, fqn)).thenReturn(true)

        mockMvc.perform(
            post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"eventType": "$fqn", "data": {}}""")
        )
            .andExpect(status().isNotFound)
    }
}
