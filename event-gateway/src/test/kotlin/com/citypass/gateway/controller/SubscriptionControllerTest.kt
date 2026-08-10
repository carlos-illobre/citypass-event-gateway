package com.citypass.gateway.controller

import com.citypass.gateway.model.Subscription
import com.citypass.gateway.service.SubscriptionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus

class SubscriptionControllerTest {

    private val subscriptionService: SubscriptionService = mock()
    private lateinit var controller: SubscriptionController

    @BeforeEach
    fun setUp() {
        controller = SubscriptionController(subscriptionService)
    }

    @Test
    fun `register returns 400 when topic is missing`() {
        val request = mapOf("callbackUrl" to "http://localhost/hook")
        val response = controller.register(request)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `register returns 400 when callbackUrl is missing`() {
        val request = mapOf("topic" to "test.topic")
        val response = controller.register(request)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `register returns 201 when valid request`() {
        val sub = Subscription(id = "s-1", topic = "test.topic", callbackUrl = "http://localhost/hook")
        whenever(subscriptionService.register("test.topic", "http://localhost/hook")).thenReturn(sub)

        val request = mapOf("topic" to "test.topic", "callbackUrl" to "http://localhost/hook")
        val response = controller.register(request)

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertEquals(sub, response.body)
    }

    @Test
    fun `list returns all subscriptions`() {
        val subs = listOf(Subscription(topic = "t1", callbackUrl = "http://c1"))
        whenever(subscriptionService.getAll()).thenReturn(subs)

        val response = controller.list()
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(subs, response.body)
    }

    @Test
    fun `unregister returns 204 when subscription found`() {
        whenever(subscriptionService.unregister("s-1")).thenReturn(true)
        val response = controller.unregister("s-1")
        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
    }

    @Test
    fun `unregister returns 404 when subscription not found`() {
        whenever(subscriptionService.unregister("s-99")).thenReturn(false)
        val response = controller.unregister("s-99")
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }
}
