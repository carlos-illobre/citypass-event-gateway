package com.citypass.gateway.service

import com.citypass.gateway.model.Subscription
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.util.concurrent.Executors

@Service
class WebhookDeliveryService(private val dlqService: DlqService) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val restClient = RestClient.builder()
        .requestFactory(JdkClientHttpRequestFactory(
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        ))
        .build()

    fun deliver(subscription: Subscription, event: Map<String, Any?>) {
        executor.submit { deliverWithRetry(subscription, event) }
    }

    private fun deliverWithRetry(subscription: Subscription, event: Map<String, Any?>, maxRetries: Int = 3) {
        var lastError: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                restClient.post()
                    .uri(subscription.callbackUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .toBodilessEntity()
                logger.info("Webhook delivered to ${subscription.callbackUrl} (sub ${subscription.id})")
                return
            } catch (e: Exception) {
                lastError = e
                logger.warn("Webhook attempt ${attempt + 1}/$maxRetries to ${subscription.callbackUrl} failed: ${e.message}")
                if (attempt < maxRetries - 1) Thread.sleep(2000)
            }
        }
        logger.error("Giving up on webhook delivery to ${subscription.callbackUrl} after $maxRetries attempts, sending to DLQ")
        lastError?.let { error ->
            dlqService.sendWebhookFailure(
                originalTopic = subscription.topic,
                originalKey = null,
                eventJson = event,
                callbackUrl = subscription.callbackUrl,
                retryCount = maxRetries,
                error = error
            )
        }
    }
}
