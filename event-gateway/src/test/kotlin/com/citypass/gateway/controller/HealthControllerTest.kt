package com.citypass.gateway.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class HealthControllerTest {

    private val controller = HealthController()

    @Test
    fun `health returns status UP`() {
        val response = controller.health()
        assertEquals(HttpStatus.OK, response.statusCode)
    }
}
