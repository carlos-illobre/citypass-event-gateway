package com.citypass.gateway.service

import com.citypass.gateway.model.Subscription
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.MessageListener
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Service
class SubscriptionService(
    private val consumerFactory: ConsumerFactory<String, ByteArray>,
    private val avroDeserializerService: AvroDeserializerService,
    private val webhookDeliveryService: WebhookDeliveryService,
    private val dlqService: DlqService,
    @Value("\${proxy.data-dir}") private val dataDir: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val containers = ConcurrentHashMap<String, ConcurrentMessageListenerContainer<String, ByteArray>>()
    private val dataFile get() = File(dataDir, "subscriptions.json")

    @PostConstruct
    fun loadFromDisk() {
        File(dataDir).mkdirs()
        if (!dataFile.exists()) return
        try {
            val loaded: List<Subscription> = mapper.readValue(dataFile)
            loaded.forEach { sub ->
                subscriptions[sub.id] = sub
                ensureConsumer(sub.topic)
            }
            logger.info("Loaded ${loaded.size} subscriptions from disk")
        } catch (e: Exception) {
            logger.error("Failed to load subscriptions from disk: ${e.message}")
        }
    }

    fun register(topic: String, callbackUrl: String): Subscription {
        val sub = Subscription(topic = topic, callbackUrl = callbackUrl)
        subscriptions[sub.id] = sub
        ensureConsumer(topic)
        saveToDisk()
        logger.info("Registered webhook for topic '$topic' → $callbackUrl (id: ${sub.id})")
        return sub
    }

    fun unregister(id: String): Boolean {
        val sub = subscriptions.remove(id) ?: return false
        if (subscriptions.values.none { it.topic == sub.topic }) {
            containers.remove(sub.topic)?.stop()
            logger.info("Stopped consumer for topic '${sub.topic}' (no more subscribers)")
        }
        saveToDisk()
        return true
    }

    fun getAll(): Collection<Subscription> = subscriptions.values

    private fun saveToDisk() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(dataFile, subscriptions.values)
            logger.debug("Subscriptions saved to ${dataFile.absolutePath}")
        } catch (e: Exception) {
            logger.error("Failed to save subscriptions to disk: ${e.message}")
        }
    }

    private fun ensureConsumer(topic: String) {
        if (containers.containsKey(topic)) return

        val props = consumerFactory.configurationProperties.toMutableMap()
        props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "latest"
        props[ConsumerConfig.GROUP_ID_CONFIG] = "event-gateway-webhook-$topic"
        val factory = DefaultKafkaConsumerFactory<String, ByteArray>(props)

        val containerProps = ContainerProperties(topic)
        containerProps.setMessageListener(MessageListener<String, ByteArray> { record ->
            dispatch(record)
        })

        val container = ConcurrentMessageListenerContainer(factory, containerProps)
        container.start()
        containers[topic] = container
        logger.info("Started dynamic Kafka consumer for topic '$topic'")
    }

    private fun dispatch(record: ConsumerRecord<String, ByteArray>) {
        val subscribers = subscriptions.values.filter { it.topic == record.topic() }
        if (subscribers.isEmpty()) return
        try {
            val event = avroDeserializerService.deserialize(record.value())
            subscribers.forEach { webhookDeliveryService.deliver(it, event) }
        } catch (e: Exception) {
            logger.error("Failed to deserialize message from '${record.topic()}', sending to DLQ: ${e.message}")
            dlqService.sendDeserializationFailure(record.topic(), record.key(), record.value(), e)
        }
    }
}
