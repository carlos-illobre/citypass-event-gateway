package com.citypass.gateway.integration

import com.citypass.gateway.controller.SubscriptionController
import com.citypass.gateway.model.Subscription
import com.citypass.gateway.service.SubscriptionService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.mockito.kotlin.mock
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class SubscriptionControllerIntegrationTest {

    private val subscriptionService: SubscriptionService = mock()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = SubscriptionController(subscriptionService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build()
    }

    @Test
    fun `GET subscriptions returns empty array`() {
        whenever(subscriptionService.getAll()).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/subscriptions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isEmpty)
    }

    @Test
    fun `POST subscriptions registers new webhook`() {
        val sub = Subscription(id = "sub-123", topic = "movilidad.bici", callbackUrl = "http://localhost/hook")
        whenever(subscriptionService.register("movilidad.bici", "http://localhost/hook")).thenReturn(sub)

        mockMvc.perform(
            post("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"topic": "movilidad.bici", "callbackUrl": "http://localhost/hook"}""")
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value("sub-123"))
            .andExpect(jsonPath("$.topic").value("movilidad.bici"))
    }

    @Test
    fun `DELETE subscriptions returns 204 on success`() {
        whenever(subscriptionService.unregister("sub-123")).thenReturn(true)

        mockMvc.perform(delete("/api/v1/subscriptions/sub-123"))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE subscriptions returns 404 when not found`() {
        whenever(subscriptionService.unregister("unknown")).thenReturn(false)

        mockMvc.perform(delete("/api/v1/subscriptions/unknown"))
            .andExpect(status().isNotFound)
    }
}
