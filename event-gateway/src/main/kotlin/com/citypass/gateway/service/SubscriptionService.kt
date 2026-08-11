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

/**
 * Servicio de gestión de suscripciones webhook.
 *
 * Responsabilidades:
 * - Registrar y eliminar suscripciones webhook (tópico → callbackUrl).
 * - Persistir suscripciones en disco (subscriptions.json) para sobrevivir reinicios.
 * - Levantar consumers Kafka dinámicos por cada tópico suscrito.
 * - Deserializar mensajes Avro y entregarlos via webhook a los suscriptores.
 * - Enviar mensajes fallidos a la DLQ si la deserialización falla.
 *
 * Se crea un consumer Kafka por tópico (no por suscripción). Si hay 3 suscriptores
 * al mismo tópico, los 3 reciben cada mensaje del mismo consumer.
 *
 * @param consumerFactory Factory de Spring Kafka para crear consumers.
 * @param avroDeserializerService Servicio de deserialización Avro → Map.
 * @param webhookDeliveryService Servicio de entrega HTTP con reintentos.
 * @param dlqService Servicio de Dead Letter Queue para mensajes fallidos.
 * @param dataDir Directorio donde se persiste subscriptions.json (variable de entorno DATA_DIR).
 */
@Service
class SubscriptionService(
    private val consumerFactory: ConsumerFactory<String, ByteArray>,
    private val avroService: AvroService,
    private val webhookDeliveryService: WebhookDeliveryService,
    private val dlqService: DlqService,
    @Value("\${gateway.data-dir}") private val dataDir: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    internal val containers = ConcurrentHashMap<String, ConcurrentMessageListenerContainer<String, ByteArray>>()
    private val dataFile get() = File(dataDir, "subscriptions.json")

    /**
     * Carga suscripciones persistidas desde disco al arrancar.
     *
     * Lee subscriptions.json y recrea los consumers Kafka para cada tópico suscrito.
     */
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

    /**
     * Registra una nueva suscripción webhook.
     *
     * Crea la suscripción, inicia un consumer Kafka para el tópico si no existe,
     * y persiste el estado a disco.
     *
     * @param topic Tópico Kafka al que suscribirse.
     * @param callbackUrl URL HTTP donde se entregarán los eventos.
     * @return Suscripción creada con su ID generado.
     */
    fun register(topic: String, callbackUrl: String): Subscription {
        val sub = Subscription(topic = topic, callbackUrl = callbackUrl)
        subscriptions[sub.id] = sub
        ensureConsumer(topic)
        saveToDisk()
        logger.info("Registered webhook for topic '$topic' → $callbackUrl (id: ${sub.id})")
        return sub
    }

    /**
     * Elimina una suscripción webhook por su ID.
     *
     * Si no quedan más suscriptores para el tópico, detiene el consumer Kafka.
     *
     * @param id UUID de la suscripción a eliminar.
     * @return true si existía y fue eliminada, false si no se encontró.
     */
    fun unregister(id: String): Boolean {
        val sub = subscriptions.remove(id) ?: return false
        if (subscriptions.values.none { it.topic == sub.topic }) {
            containers.remove(sub.topic)!!.stop()
            logger.info("Stopped consumer for topic '${sub.topic}' (no more subscribers)")
        }
        saveToDisk()
        return true
    }

    /**
     * Retorna todas las suscripciones activas.
     *
     * @return Colección de suscripciones registradas.
     */
    fun getAll(): Collection<Subscription> = subscriptions.values

    /**
     * Persiste las suscripciones actuales a disco en formato JSON.
     */
    private fun saveToDisk() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(dataFile, subscriptions.values)
        } catch (e: Exception) {
            logger.error("Failed to save subscriptions to disk: ${e.message}")
        }
    }

    /**
     * Asegura que exista un consumer Kafka activo para el tópico dado.
     *
     * Si ya existe un consumer para ese tópico, no hace nada.
     * Si no existe, crea uno nuevo con un group ID único por tópico.
     *
     * @param topic Tópico Kafka para el cual crear el consumer.
     */
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

    /**
     * Despacha un mensaje Kafka a todos los suscriptores del tópico.
     *
     * Deserializa el mensaje Avro y lo entrega a cada suscriptor via webhook.
     * Si la deserialización falla, envía el mensaje crudo a la DLQ.
     *
     * Internal para permitir tests directos sin necesitar un consumer Kafka real.
     *
     * @param record Registro Kafka recibido del consumer.
     */
    internal fun dispatch(record: ConsumerRecord<String, ByteArray>) {
        val subscribers = subscriptions.values.filter { it.topic == record.topic() }
        if (subscribers.isEmpty()) return
        try {
            val event = avroService.deserialize(record.value())
            subscribers.forEach { webhookDeliveryService.deliver(it, event) }
        } catch (e: Exception) {
            logger.error("Failed to deserialize message from '${record.topic()}', sending to DLQ: ${e.message}")
            dlqService.sendDeserializationFailure(record.topic(), record.key(), record.value(), e)
        }
    }
}
