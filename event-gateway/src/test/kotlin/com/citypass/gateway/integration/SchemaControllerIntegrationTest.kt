package com.citypass.gateway.integration

import com.citypass.gateway.controller.SchemaController
import com.citypass.gateway.service.SchemaRegistryService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

@Tag("integration")
class SchemaControllerIntegrationTest {

    private val schemaRegistryService: SchemaRegistryService = mock()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = SchemaController(schemaRegistryService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build()
    }

    @Test
    fun `GET schemas returns available schema list`() {
        whenever(schemaRegistryService.getAvailableEventTypes()).thenReturn(setOf("bici.devuelta", "subte.validado"))

        mockMvc.perform(get("/api/v1/schemas"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eventTypes").isArray)
            .andExpect(jsonPath("$.eventTypes.length()").value(2))
    }
}
