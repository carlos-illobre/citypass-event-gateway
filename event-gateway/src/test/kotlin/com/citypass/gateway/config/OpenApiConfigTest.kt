package com.citypass.gateway.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class OpenApiConfigTest {

    @Test
    fun `openApi bean returns valid OpenAPI document`() {
        val config = OpenApiConfig()
        val openApi = config.openApi()

        assertNotNull(openApi)
        assertNotNull(openApi.info)
        assertEquals("CityPass+ EDA - Event Gateway", openApi.info.title)
        assertEquals("1.0.0", openApi.info.version)
    }
}
