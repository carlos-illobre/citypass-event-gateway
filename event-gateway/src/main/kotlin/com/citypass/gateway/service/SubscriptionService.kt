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
    fun register(topic: String, callbackUrl: String, owner: String, createdBy: String): Subscription {
        val sub = Subscription(topic = topic, callbackUrl = callbackUrl, owner = owner, createdBy = createdBy)
        subscriptions[sub.id] = sub
        ensureConsumer(topic)
        saveToDisk()
        logger.info("Registered webhook for topic '$topic' → $callbackUrl (id: ${sub.id}, owner: $owner)")
        return sub
    }

    /**
     * Elimina una suscripción propia.
     *
     * Una suscripción ajena se trata como inexistente: sin esta comprobación, cualquier
     * grupo autenticado podría cortarle la entrega de eventos a otro, y del lado de la
     * víctima se vería sólo como que los webhooks dejaron de llegar.
     *
     * Si no quedan más suscriptores para el tópico, detiene el consumer Kafka.
     *
     * @param id UUID de la suscripción a eliminar.
     * @param owner Namespace del grupo que pide la baja.
     * @return true si existía, era suya y fue eliminada.
     */
    fun unregister(id: String, owner: String): Boolean {
        val sub = subscriptions[id]
        if (sub == null || sub.owner != owner) return false
        subscriptions.remove(id)
        if (subscriptions.values.none { it.topic == sub.topic }) {
            containers.remove(sub.topic)!!.stop()
            logger.info("Stopped consumer for topic '${sub.topic}' (no more subscribers)")
        }
        saveToDisk()
        return true
    }

    /**
     * Suscripciones a cualquiera de los tópicos dados, de cualquier dueño.
     *
     * Se usa antes de borrar un event type: hay que saber a quién se estaría dejando sin
     * eventos, y esa pregunta no se puede acotar a las suscripciones propias.
     *
     * @param topics Tópicos a consultar.
     */
    fun suscriptoresA(topics: Collection<String>): List<Subscription> =
        subscriptions.values.filter { it.topic in topics }

    /**
     * Da de baja todas las suscripciones a los tópicos dados y detiene sus consumers.
     *
     * Acompaña al borrado de un event type: una suscripción a un tópico que ya no existe
     * no vuelve a entregar nada, así que dejarla sería dejar basura que aparenta funcionar.
     *
     * @param topics Tópicos cuyas suscripciones se eliminan.
     * @return Cuántas suscripciones se dieron de baja.
     */
    fun unregisterTopics(topics: Collection<String>): Int {
        val bajas = subscriptions.values.filter { it.topic in topics }
        bajas.forEach { subscriptions.remove(it.id) }
        topics.forEach { topic ->
            containers.remove(topic)?.let {
                it.stop()
                logger.info("Stopped consumer for topic '$topic' (event type borrado)")
            }
        }
        if (bajas.isNotEmpty()) saveToDisk()
        return bajas.size
    }

    /**
     * Suscripciones de un grupo.
     *
     * No se listan las ajenas: expondrían las URLs internas de los otros equipos y los
     * ids con los que darlas de baja.
     *
     * @param owner Namespace del grupo que consulta.
     */
    fun getAll(owner: String): Collection<Subscription> =
        subscriptions.values.filter { it.owner == owner }

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

        val factory = DefaultKafkaConsumerFactory<String, ByteArray>(consumerProps(topic))

        val containerProps = ContainerProperties(topic)
        // RECORD: confirma el offset después de que el listener procesó cada registro.
        containerProps.ackMode = ContainerProperties.AckMode.RECORD
        containerProps.setMessageListener(MessageListener<String, ByteArray> { record ->
            dispatch(record)
        })

        val container = ConcurrentMessageListenerContainer(factory, containerProps)
        container.start()
        containers[topic] = container
        logger.info("Started dynamic Kafka consumer for topic '$topic'")
    }

    /**
     * Propiedades del consumer de un tópico.
     *
     * `enable.auto.commit=false` es media garantía de que no se pierdan eventos: con
     * auto-commit el offset avanza por reloj, sin relación con si el evento llegó a
     * entregarse. La otra media es el `ackMode` RECORD del contenedor.
     *
     * Internal para que un test pueda afirmarlo: si alguien lo cambia, todo sigue
     * compilando y el resto de los tests sigue pasando, pero los eventos vuelven a
     * perderse en cada reinicio y en silencio.
     *
     * @param topic Tópico al que se suscribe el consumer.
     */
    internal fun consumerProps(topic: String): Map<String, Any> =
        consumerFactory.configurationProperties.toMutableMap().apply {
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
            put(ConsumerConfig.GROUP_ID_CONFIG, "event-gateway-webhook-$topic")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
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
            // Bloquea hasta entregar (o registrar el fallo en la DLQ). Con ackMode RECORD,
            // el offset se confirma recién cuando este método vuelve: si el gateway muere
            // en el medio, el evento se relee en vez de perderse.
            webhookDeliveryService.deliverAll(subscribers, event)
        } catch (e: Exception) {
            logger.error("Failed to deserialize message from '${record.topic()}', sending to DLQ: ${e.message}")
            dlqService.sendDeserializationFailure(record.topic(), record.key(), record.value(), e)
        }
    }
}
