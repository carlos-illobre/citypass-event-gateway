package com.citypass.gateway.service

import com.citypass.gateway.model.Subscription
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.*
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.MessageListener
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

class SubscriptionServiceTest {

    private val OWNER = "com.citypass.movilidad"

    private val consumerFactory: ConsumerFactory<String, ByteArray> = mock()
    private val avroService: AvroService = mock()
    private val webhookDeliveryService: WebhookDeliveryService = mock()
    private val dlqService: DlqService = mock()

    @TempDir
    lateinit var tempDir: File

    private lateinit var subscriptionService: SubscriptionService

    @BeforeEach
    fun setUp() {
        whenever(consumerFactory.configurationProperties).thenReturn(mapOf(
            // Puerto sin nada escuchando a propósito: `register` levanta un consumer
            // Kafka real, y contra el broker de docker-compose terminaba creando
            // tópicos de prueba (topic.a, topic.b, …) en el entorno de desarrollo.
            "bootstrap.servers" to "localhost:1",
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "org.apache.kafka.common.serialization.ByteArrayDeserializer",
            "group.id" to "test-group"
        ))
        subscriptionService = SubscriptionService(
            consumerFactory = consumerFactory,
            avroService = avroService,
            webhookDeliveryService = webhookDeliveryService,
            dlqService = dlqService,
            dataDir = tempDir.absolutePath,
            maxPorEventType = 3
        )
    }

    // ── register / unregister / getAll ──────────────────────────────────────

    @Test
    fun `register creates subscription and stores in memory`() {
        val sub = subscriptionService.register("topic.a", "http://localhost/hook", OWNER, "usuario1").getOrThrow()

        assertNotNull(sub.id)
        assertEquals("topic.a", sub.topic)
        assertEquals("http://localhost/hook", sub.callbackUrl)

        val all = subscriptionService.getAll(OWNER)
        assertEquals(1, all.size)
        assertTrue(all.contains(sub))
    }

    @Test
    fun `register second subscription on same topic reuses existing consumer`() {
        subscriptionService.register("topic.shared", "http://localhost/hook1", OWNER, "usuario1").getOrThrow()
        subscriptionService.register("topic.shared", "http://localhost/hook2", OWNER, "usuario1").getOrThrow()
        assertEquals(2, subscriptionService.getAll(OWNER).size)
    }

    @Test
    fun `unregister removes subscription and returns true`() {
        val sub = subscriptionService.register("topic.b", "http://localhost/hook2", OWNER, "usuario1").getOrThrow()
        val removed = subscriptionService.unregister(sub.id, OWNER)

        assertTrue(removed)
        assertTrue(subscriptionService.getAll(OWNER).isEmpty())
    }

    @Test
    fun `unregister refuses to delete a subscription of another group`() {
        val ajena = subscriptionService.register("topic.a", "http://localhost/hook", "com.citypass.reclamos", "otro").getOrThrow()

        val removed = subscriptionService.unregister(ajena.id, OWNER)

        // Sin esta comprobación, cualquier grupo autenticado podría cortarle la entrega
        // de eventos a otro, y del lado de la víctima se vería sólo como que los
        // webhooks dejaron de llegar.
        assertFalse(removed)
        assertEquals(1, subscriptionService.getAll("com.citypass.reclamos").size)
    }

    @Test
    fun `getAll only returns the subscriptions of the given group`() {
        subscriptionService.register("topic.a", "http://localhost/hook", OWNER, "usuario1").getOrThrow()
        subscriptionService.register("topic.b", "http://localhost/hook", "com.citypass.reclamos", "otro").getOrThrow()

        assertEquals(1, subscriptionService.getAll(OWNER).size)
        assertEquals(OWNER, subscriptionService.getAll(OWNER).single().owner)
    }

    @Test
    fun `unregister returns false for non existent id`() {
        val removed = subscriptionService.unregister("non-existent-id", OWNER)
        assertFalse(removed)
    }

    @Test
    fun `unregister keeps consumer when another subscriber remains on same topic`() {
        val sub1 = subscriptionService.register("topic.shared", "http://localhost/hook1", OWNER, "usuario1").getOrThrow()
        subscriptionService.register("topic.shared", "http://localhost/hook2", OWNER, "usuario1").getOrThrow()

        val removed = subscriptionService.unregister(sub1.id, OWNER)

        assertTrue(removed)
        assertEquals(1, subscriptionService.getAll(OWNER).size)
    }

    // ── loadFromDisk ─────────────────────────────────────────────────────────

    @Test
    fun `loadFromDisk handles empty directory gracefully`() {
        subscriptionService.loadFromDisk()
        assertTrue(subscriptionService.getAll(OWNER).isEmpty())
    }

    @Test
    fun `loadFromDisk restores subscriptions persisted to disk`() {
        val sub1 = Subscription(topic = "topic.a", callbackUrl = "http://localhost/hook1", owner = OWNER)
        val sub2 = Subscription(topic = "topic.b", callbackUrl = "http://localhost/hook2", owner = OWNER)
        val mapper = jacksonObjectMapper()
        File(tempDir, "subscriptions.json").writeText(mapper.writeValueAsString(listOf(sub1, sub2)))

        subscriptionService.loadFromDisk()

        assertEquals(2, subscriptionService.getAll(OWNER).size)
    }

    @Test
    fun `loadFromDisk handles malformed json gracefully`() {
        File(tempDir, "subscriptions.json").writeText("not valid json [[[")

        assertDoesNotThrow { subscriptionService.loadFromDisk() }
        assertTrue(subscriptionService.getAll(OWNER).isEmpty())
    }

    @Test
    fun `register handles disk write failure gracefully`() {
        // Make tempDir read-only to force saveToDisk IOException
        tempDir.setReadOnly()
        try {
            assertDoesNotThrow { subscriptionService.register("topic.x", "http://example.com", OWNER, "usuario1").getOrThrow() }
        } finally {
            tempDir.setWritable(true)
        }
    }

    // ── message listener wiring ──────────────────────────────────────────────

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `ensureConsumer message listener delegates to dispatch`() {
        subscriptionService.register("topic.wired", "http://localhost/hook", OWNER, "usuario1").getOrThrow()
        val rawBytes = byteArrayOf(9, 8, 7)
        val event = mapOf<String, Any?>("eventId" to "e-wired")
        whenever(avroService.deserialize(rawBytes)).thenReturn(event)

        val container = subscriptionService.containers["topic.wired"]!!
        val listener = container.containerProperties.messageListener as MessageListener<String, ByteArray>
        val record = ConsumerRecord<String, ByteArray>("topic.wired", 0, 0L, "k", rawBytes)
        listener.onMessage(record)

        verify(webhookDeliveryService).deliverAll(any(), eq(event))
    }

    // ── dispatch ─────────────────────────────────────────────────────────────

    @Test
    fun `dispatch does nothing when no subscribers for topic`() {
        val record = ConsumerRecord<String, ByteArray>("topic.a", 0, 0L, "key", byteArrayOf())
        subscriptionService.dispatch(record)
        verifyNoInteractions(avroService, webhookDeliveryService, dlqService)
    }

    @Test
    fun `dispatch delivers deserialized event to all subscribers of topic`() {
        val sub = subscriptionService.register("topic.a", "http://localhost/hook", OWNER, "usuario1").getOrThrow()
        val rawBytes = byteArrayOf(1, 2, 3)
        val event = mapOf<String, Any?>("eventId" to "uuid-1")
        whenever(avroService.deserialize(rawBytes)).thenReturn(event)

        val record = ConsumerRecord<String, ByteArray>("topic.a", 0, 0L, "key", rawBytes)
        subscriptionService.dispatch(record)

        verify(webhookDeliveryService).deliverAll(listOf(sub), event)
    }

    @Test
    fun `dispatch sends to DLQ when deserialization fails`() {
        subscriptionService.register("topic.a", "http://localhost/hook", OWNER, "usuario1").getOrThrow()
        val rawBytes = byteArrayOf(1, 2, 3)
        val exception = RuntimeException("bad avro")
        whenever(avroService.deserialize(rawBytes)).thenThrow(exception)

        val record = ConsumerRecord<String, ByteArray>("topic.a", 0, 0L, "key-1", rawBytes)
        subscriptionService.dispatch(record)

        verify(dlqService).sendDeserializationFailure("topic.a", "key-1", rawBytes, exception)
        verifyNoInteractions(webhookDeliveryService)
    }

    // ── durabilidad de la entrega ─────────────────────────────────────────────

    @Test
    fun `el consumer se crea con commit manual y ack por registro`() {
        // Estas dos propiedades son toda la garantía de que un evento no se pierde: con
        // auto-commit el offset avanza por reloj, y sin ackMode RECORD el contenedor no
        // espera a que el listener termine. Si alguien las cambia, el sistema sigue
        // compilando y todos los demás tests siguen pasando, pero los eventos vuelven a
        // perderse en cada reinicio — en silencio.
        subscriptionService.register("topic.durable", "http://localhost/hook", OWNER, "usuario1").getOrThrow()

        val container = subscriptionService.containers["topic.durable"]!!
        assertEquals(ContainerProperties.AckMode.RECORD, container.containerProperties.ackMode)
        assertEquals(false, subscriptionService.consumerProps("topic.durable")["enable.auto.commit"])
        // El group.id es estable entre reinicios: si cambiara, el consumer nuevo no
        // encontraría el offset del anterior y arrancaría desde el final, saltándose
        // justamente los eventos que quedaron sin entregar.
        assertEquals("event-gateway-webhook-topic.durable",
            subscriptionService.consumerProps("topic.durable")["group.id"])
    }

    // ── borrado de un event type ─────────────────────────────────────────────

    @Test
    fun `suscriptoresA lists every subscriber, including other teams`() {
        subscriptionService.register("topic.uno", "http://a", OWNER, "u1").getOrThrow()
        subscriptionService.register("topic.uno", "http://b", "com.citypass.otros", "u2").getOrThrow()
        subscriptionService.register("topic.dos", "http://c", OWNER, "u3").getOrThrow()

        val suscriptores = subscriptionService.suscriptoresA(listOf("topic.uno"))

        // No se filtra por dueño: antes de borrar hay que saber a quién se estaría
        // dejando sin eventos, y eso incluye a los otros equipos.
        assertEquals(2, suscriptores.size)
        assertEquals(setOf(OWNER, "com.citypass.otros"), suscriptores.map { it.owner }.toSet())
    }

    @Test
    fun `suscriptoresA returns nothing for a topic without subscribers`() {
        subscriptionService.register("topic.uno", "http://a", OWNER, "u1").getOrThrow()

        assertTrue(subscriptionService.suscriptoresA(listOf("topic.sin.nadie")).isEmpty())
    }

    @Test
    fun `unregisterTopics drops the subscriptions and stops their consumers`() {
        subscriptionService.register("topic.uno", "http://a", OWNER, "u1").getOrThrow()
        subscriptionService.register("topic.uno", "http://b", "com.citypass.otros", "u2").getOrThrow()
        subscriptionService.register("topic.queda", "http://c", OWNER, "u3").getOrThrow()

        val bajas = subscriptionService.unregisterTopics(listOf("topic.uno"))

        assertEquals(2, bajas)
        // Una suscripción a un tópico que ya no existe no vuelve a entregar nada: dejarla
        // sería dejar basura que aparenta funcionar.
        assertTrue(subscriptionService.suscriptoresA(listOf("topic.uno")).isEmpty())
        assertFalse(subscriptionService.containers.containsKey("topic.uno"))
        // Lo de los demás tópicos queda intacto.
        assertEquals(1, subscriptionService.suscriptoresA(listOf("topic.queda")).size)
        assertTrue(subscriptionService.containers.containsKey("topic.queda"))
    }

    @Test
    fun `unregisterTopics on a topic nobody subscribed to changes nothing`() {
        subscriptionService.register("topic.uno", "http://a", OWNER, "u1").getOrThrow()

        assertEquals(0, subscriptionService.unregisterTopics(listOf("topic.sin.nadie")))
        assertEquals(1, subscriptionService.getAll(OWNER).size)
    }

    @Test
    fun `unregisterTopics survives a restart`() {
        subscriptionService.register("topic.uno", "http://a", OWNER, "u1").getOrThrow()
        subscriptionService.unregisterTopics(listOf("topic.uno"))

        val reiniciado = SubscriptionService(
            consumerFactory, avroService, webhookDeliveryService, dlqService, tempDir.absolutePath, 3
        )
        reiniciado.loadFromDisk()

        assertTrue(reiniciado.getAll(OWNER).isEmpty())
    }

    // ── techo de webhooks por event type ─────────────────────────────────────

    @Test
    fun `un equipo no puede pasarse del máximo de webhooks sobre un tópico`() {
        // El techo acota el factor de amplificación: la entrega bloquea al consumer
        // hasta atender a TODOS los suscriptores, así que sin límite un solo evento
        // puede disparar miles de peticiones salientes antes de que el tópico avance.
        repeat(3) { subscriptionService.register("topic.tope", "http://a$it", OWNER, "u") }

        val result = subscriptionService.register("topic.tope", "http://uno-mas", OWNER, "u")

        assertTrue(result.isFailure)
        assertInstanceOf(CupoAgotadoException::class.java, result.exceptionOrNull())
        assertEquals(3, subscriptionService.suscriptoresA(listOf("topic.tope")).size)
    }

    @Test
    fun `el techo es por tópico, no global`() {
        repeat(3) { subscriptionService.register("topic.lleno", "http://a$it", OWNER, "u") }

        assertTrue(subscriptionService.register("topic.otro", "http://b", OWNER, "u").isSuccess)
    }

    @Test
    fun `el techo es por equipo, no compartido entre equipos`() {
        repeat(3) { subscriptionService.register("topic.compartido", "http://a$it", OWNER, "u") }

        // Otro equipo tiene su propio cupo sobre el mismo tópico: el de uno no puede
        // dejar sin webhooks al otro.
        assertTrue(
            subscriptionService.register("topic.compartido", "http://otro", "com.citypass.otros", "u").isSuccess
        )
    }

    @Test
    fun `dar de baja libera lugar`() {
        val primera = subscriptionService.register("topic.libera", "http://a", OWNER, "u").getOrThrow()
        repeat(2) { subscriptionService.register("topic.libera", "http://b$it", OWNER, "u") }
        assertTrue(subscriptionService.register("topic.libera", "http://c", OWNER, "u").isFailure)

        subscriptionService.unregister(primera.id, OWNER)

        assertTrue(subscriptionService.register("topic.libera", "http://c", OWNER, "u").isSuccess)
    }
}
