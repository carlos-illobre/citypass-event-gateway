package com.citypass.gateway.config

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class RestClientConfigTest {

    private val config = RestClientConfig()

    @Test
    fun `restClient bean is created and is not null`() {
        val restClient = config.restClient()
        assertNotNull(restClient)
    }
}
