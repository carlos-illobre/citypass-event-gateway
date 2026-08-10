package com.citypass.gateway.service

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class DlqService(
    private val kafkaTemplate: KafkaTemplate<String, ByteArray>,
    @Value("\${proxy.dlq-topic:sistema.dlq}") private val dlqTopic: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    fun sendDeserializationFailure(topic: String, key: String?, rawValue: ByteArray, error: Exception) {
        send(
            originalTopic = topic,
            originalKey = key,
            originalPayloadBase64 = Base64.getEncoder().encodeToString(rawValue),
            failureReason = "DESERIALIZATION_ERROR",
            errorMessage = error.message ?: "Unknown error",
            retryCount = 0
        )
    }

    fun sendWebhookFailure(
        originalTopic: String,
        originalKey: String?,
        eventJson: Map<String, Any?>,
        callbackUrl: String,
        retryCount: Int,
        error: Exception
    ) {
        send(
            originalTopic = originalTopic,
            originalKey = originalKey,
            originalPayloadBase64 = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(eventJson)),
            failureReason = "WEBHOOK_DELIVERY_FAILED",
            errorMessage = "Max retries ($retryCount) exceeded for $callbackUrl: ${error.message}",
            retryCount = retryCount
        )
    }

    private fun send(
        originalTopic: String,
        originalKey: String?,
        originalPayloadBase64: String,
        failureReason: String,
        errorMessage: String,
        retryCount: Int
    ) {
        val dlqMessage = mapOf(
            "dlqId" to UUID.randomUUID().toString(),
            "timestamp" to Instant.now().toString(),
            "failureReason" to failureReason,
            "errorMessage" to errorMessage,
            "retryCount" to retryCount,
            "originalTopic" to originalTopic,
            "originalKey" to originalKey,
            "originalPayloadBase64" to originalPayloadBase64
        )
        val json = mapper.writeValueAsString(dlqMessage).toByteArray()
        kafkaTemplate.send(ProducerRecord(dlqTopic, originalKey, json))
        logger.warn("DLQ [$failureReason] topic=$originalTopic error=$errorMessage")
    }
}
