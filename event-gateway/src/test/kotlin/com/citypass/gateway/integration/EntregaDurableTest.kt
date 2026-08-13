package com.citypass.gateway.integration

import com.citypass.gateway.service.AvroService
import com.citypass.gateway.service.DlqService
import com.citypass.gateway.service.SubscriptionService
import com.citypass.gateway.service.WebhookDeliveryService
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import org.springframework.kafka.test.utils.ContainerTestUtils
import java.io.File
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/**
 * Comprueba contra un broker real que el offset se confirme **después** de entregar.
 *
 * Es la única garantía que impide perder eventos en un reinicio, y no se puede verificar
 * mirando la configuración: que `ackMode` sea RECORD y que el auto-commit esté apagado son
 * condiciones necesarias, pero lo que importa es el efecto combinado. Acá se mide el
 * efecto — mientras la entrega está en curso, el offset no avanza.
 *
 * Antes de este cambio la entrega se despachaba a un hilo aparte y el listener volvía
 * enseguida, así que el offset se confirmaba con el evento todavía sin entregar: un
 * reinicio en ese momento lo perdía sin dejar ni una entrada en la DLQ.
 */
@Tag("integration")
class EntregaDurableTest {

    private companion object {
        const val TOPICO = "topic.durable"
        const val GRUPO = "event-gateway-webhook-$TOPICO"

        private lateinit var broker: EmbeddedKafkaKraftBroker

        @BeforeAll
        @JvmStatic
        fun levantarBroker() {
            broker = EmbeddedKafkaKraftBroker(1, 1, TOPICO)
            broker.afterPropertiesSet()
        }

        @AfterAll
        @JvmStatic
        fun bajarBroker() = broker.destroy()
    }

    @TempDir
    lateinit var tempDir: File

    private val avroService: AvroService = mock()
    private val dlqService: DlqService = mock()
    private val webhookDeliveryService: WebhookDeliveryService = mock()

    /** Offset confirmado del grupo, o null si todavía no confirmó ninguno. */
    private fun offsetConfirmado(): Long? {
        val props = Properties().apply {
            put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.brokersAsString)
        }
        AdminClient.create(props).use { admin ->
            val offsets = admin.listConsumerGroupOffsets(GRUPO)
                .partitionsToOffsetAndMetadata().get()
            return offsets[TopicPartition(TOPICO, 0)]?.offset()
        }
    }

    private fun publicar(cuantos: Int) {
        val props = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.brokersAsString)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArraySerializer")
        }
        KafkaProducer<String, ByteArray>(props).use { productor ->
            repeat(cuantos) { i ->
                productor.send(ProducerRecord(TOPICO, "k$i", byteArrayOf(i.toByte()))).get()
            }
        }
    }

    @Test
    fun `el offset avanza evento por evento, sólo despues de entregar cada uno`() {
        val segundaEmpezo = CountDownLatch(1)
        val liberar = CountDownLatch(1)
        val entregas = AtomicInteger(0)

        whenever(avroService.deserialize(any())).thenReturn(mapOf("eventId" to "e-1"))
        // La primera entrega pasa de largo; la segunda avisa y se queda esperando.
        //
        // Hacen falta dos eventos para que el test discrimine. Con uno solo pasaría
        // igual con auto-commit: el commit automático ocurre dentro del `poll`, y
        // mientras el listener está bloqueado no hay poll, así que el offset tampoco
        // avanza y el test no distinguiría una configuración de la otra.
        whenever(webhookDeliveryService.deliverAll(any(), any())).doAnswer {
            if (entregas.incrementAndGet() == 2) {
                segundaEmpezo.countDown()
                liberar.await(30, TimeUnit.SECONDS)
            }
            null
        }

        val consumerFactory = DefaultKafkaConsumerFactory<String, ByteArray>(mapOf(
            "bootstrap.servers" to broker.brokersAsString,
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "org.apache.kafka.common.serialization.ByteArrayDeserializer"
        ))
        val service = SubscriptionService(
            consumerFactory, avroService, webhookDeliveryService, dlqService, tempDir.absolutePath
        )

        service.register(TOPICO, "https://ejemplo.test/hook", "com.citypass.movilidad", "usuario1")
        val container = service.containers[TOPICO]!!
        // El consumer arranca en `latest`, así que hay que esperar a que tenga la
        // partición asignada antes de publicar; si no, el evento queda antes de su
        // posición y no lo lee nunca.
        ContainerTestUtils.waitForAssignment(container, 1)

        publicar(2)
        check(segundaEmpezo.await(30, TimeUnit.SECONDS)) { "la segunda entrega nunca arrancó" }

        // Núcleo del test. Un offset confirmado es «el próximo a leer», así que 1 significa
        // exactamente: el primer evento se entregó y se confirmó, el segundo está en vuelo
        // y todavía no. Si el gateway muriera acá, se releería el segundo y no el primero.
        //
        // Con auto-commit el valor sería 0 —no hubo ningún poll desde que arrancó— y con
        // ackMode BATCH tampoco se confirmaría de a uno.
        assertEquals(
            1L, offsetConfirmado(),
            "se esperaba el primer evento confirmado y el segundo pendiente"
        )

        liberar.countDown()

        // Y una vez entregado, sí avanza.
        val plazo = System.currentTimeMillis() + 30_000
        while (offsetConfirmado() != 2L && System.currentTimeMillis() < plazo) {
            Thread.sleep(200)
        }
        assertEquals(2L, offsetConfirmado(), "el offset no avanzó después de entregar el segundo")

        container.stop()
    }
}
