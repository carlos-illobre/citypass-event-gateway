package com.citypass.gateway.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SubscriptionModelTest {

    @Test
    fun `subscription default values are populated`() {
        val sub = Subscription(topic = "test.topic", callbackUrl = "http://localhost/callback")

        assertNotNull(sub.id)
        assertNotNull(sub.createdAt)
        assertEquals("test.topic", sub.topic)
        assertEquals("http://localhost/callback", sub.callbackUrl)
    }

    @Test
    fun `subscription custom values are preserved`() {
        val sub = Subscription(
            id = "custom-id",
            topic = "custom.topic",
            callbackUrl = "http://example.com/hook",
            createdAt = "2026-01-01T00:00:00Z"
        )

        assertEquals("custom-id", sub.id)
        assertEquals("custom.topic", sub.topic)
        assertEquals("http://example.com/hook", sub.callbackUrl)
        assertEquals("2026-01-01T00:00:00Z", sub.createdAt)
    }
}
