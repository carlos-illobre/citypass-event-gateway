package com.citypass.gateway.integration

import com.citypass.gateway.controller.EventController
import com.citypass.gateway.service.AvroService
import com.citypass.gateway.service.SchemaRegistryService
import com.citypass.gateway.service.TopicAuthorizationService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class EventControllerIntegrationTest {

    private val kafkaTemplate: KafkaTemplate<String, ByteArray> = mock()
    private val schemaRegistryService: SchemaRegistryService = mock()
    private val avroService: AvroService = mock()
    private val topicAuthorizationService: TopicAuthorizationService = mock()

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = EventController(kafkaTemplate, schemaRegistryService, avroService, topicAuthorizationService, false)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
    }

    @Test
    fun `GET health returns status UP`() {
        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.service").value("event-gateway"))
    }

    @Test
    fun `GET schemas returns available schema list`() {
        whenever(schemaRegistryService.getAvailableEventTypes()).thenReturn(setOf("bici.devuelta", "subte.validado"))

        mockMvc.perform(get("/api/v1/schemas"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eventTypes").isArray)
            .andExpect(jsonPath("$.eventTypes.length()").value(2))
    }

    @Test
    fun `POST events returns 400 when missing parameters`() {
        mockMvc.perform(
            post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"source": "test"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("error"))
    }
}
