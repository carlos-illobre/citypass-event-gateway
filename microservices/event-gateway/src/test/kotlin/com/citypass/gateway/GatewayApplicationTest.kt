package com.citypass.gateway

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class GatewayApplicationTest {

    @Test
    fun `application instance can be instantiated`() {
        val app = GatewayApplication()
        assertNotNull(app)
    }
}
