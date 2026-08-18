package com.citypass.gateway.service

import org.apache.avro.Schema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.boot.info.BuildProperties
import org.springframework.core.io.ClassPathResource
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.Properties

class SchemaChangeNotifierTest {

    private val kafkaTemplate: KafkaTemplate<String, ByteArray> = mock()
    private val schemaRegistryService: SchemaRegistryService = mock()
    private val buildProperties = BuildProperties(Properties().apply { setProperty("version", "9.9.9") })

    private lateinit var avroService: AvroService
    private lateinit var notifier: SchemaChangeNotifier

    private val fqn = SchemaChangeNotifier.FQN

    /**
     * El mismo envelope que arma el gateway al registrar el event type del aviso: `data`
     * con los campos declarados en el notificador y `metadata` con el record del gateway.
     */
    private val schemaDelAviso: Schema by lazy {
        val metadata = Schema.Parser().parse(ClassPathResource("avro/event-metadata.avsc").inputStream)
        val json = jacksonObjectMapper().writeValueAsString(mapOf(
            "type" to "record",
            "name" to SchemaChangeNotifier.NAME,
            "namespace" to SchemaChangeNotifier.NAMESPACE,
            "fields" to listOf(
                mapOf("name" to "data", "type" to mapOf(
                    "type" to "record",
                    "name" to SchemaChangeNotifier.NAME,
                    "namespace" to "${SchemaChangeNotifier.NAMESPACE}.data",
                    "fields" to SchemaChangeNotifier.CAMPOS
                )),
                mapOf("name" to "metadata", "type" to metadata.fullName)
            )
        ))
        Schema.Parser().apply { addTypes(listOf(metadata)) }.parse(json)
    }

    private fun cambio(breaking: Boolean, previousTopic: String?) = CambioDeEsquema(
        fqn = "com.citypass.movilidad.BiciDevuelta",
        topic = if (breaking) "com.citypass.movilidad.BiciDevuelta.v2" else "com.citypass.movilidad.BiciDevuelta",
        version = if (breaking) 2 else 1,
        schemaId = 42,
        breaking = breaking,
        previousTopic = previousTopic,
        unchanged = false
    )

    @BeforeEach
    fun setUp() {
        avroService = AvroService(schemaRegistryService)
        notifier = SchemaChangeNotifier(kafkaTemplate, schemaRegistryService, avroService, buildProperties)
    }

    // ── registro del event type del aviso ────────────────────────────────────

    @Test
    fun `registers the notification event type at startup`() {
        whenever(schemaRegistryService.getSchema(fqn)).thenReturn(null)
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any())).thenReturn(Result.success(1))

        notifier.registrarTipoDeAviso()

        // Existe desde el arranque y no desde el primer cambio, que es cuando un equipo
        // ya no llegaría a tiempo de suscribirse.
        verify(schemaRegistryService).registerNewSchema(
            SchemaChangeNotifier.NAMESPACE, SchemaChangeNotifier.NAME, SchemaChangeNotifier.CAMPOS
        )
    }

    @Test
    fun `does not re-register the notification type if it is already on disk`() {
        whenever(schemaRegistryService.getSchema(fqn)).thenReturn(schemaDelAviso)

        notifier.registrarTipoDeAviso()

        verify(schemaRegistryService, never()).registerNewSchema(any(), any(), any())
    }

    @Test
    fun `a failure registering the notification type does not stop the startup`() {
        whenever(schemaRegistryService.getSchema(fqn)).thenReturn(null)
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("registry caído")))

        // Un bus sin el aviso funciona; uno que no arranca, no.
        assertDoesNotThrow { notifier.registrarTipoDeAviso() }
    }

    // ── publicación del aviso ────────────────────────────────────────────────

    @Test
    fun `announces a breaking change with the topic that replaced the old one`() {
        whenever(schemaRegistryService.resolver(fqn)).thenReturn(TipoResuelto(fqn, schemaDelAviso, 5))
        whenever(schemaRegistryService.getSchemaById(5)).thenReturn(schemaDelAviso)

        notifier.notificar(cambio(breaking = true, previousTopic = "com.citypass.movilidad.BiciDevuelta"), "com.citypass.movilidad")

        val bytes = argumentCaptor<ByteArray>()
        verify(kafkaTemplate).send(eq(fqn), eq("com.citypass.movilidad.BiciDevuelta"), bytes.capture())

        // Se lee de vuelta el evento publicado: es lo que va a recibir un suscriptor.
        val evento = avroService.deserialize(bytes.firstValue)
        val data = evento["data"] as Map<*, *>
        assertEquals("com.citypass.movilidad.BiciDevuelta", data["eventType"])
        assertEquals("com.citypass.movilidad.BiciDevuelta.v2", data["topic"])
        assertEquals(2, data["version"])
        assertEquals(42, data["schemaId"])
        assertEquals(true, data["breaking"])
        assertEquals("com.citypass.movilidad.BiciDevuelta", data["previousTopic"])
        assertEquals("com.citypass.movilidad", data["changedBy"])

        val metadata = evento["metadata"] as Map<*, *>
        // El emisor es la plataforma: el cambio lo pidió un equipo, pero quien da fe de
        // que ocurrió es el gateway.
        assertEquals(SchemaChangeNotifier.NAMESPACE, metadata["source"])
        assertEquals(fqn, metadata["eventType"])
        assertEquals("9.9.9", metadata["gatewayVersion"])
    }

    @Test
    fun `announces a compatible change with previousTopic null`() {
        whenever(schemaRegistryService.resolver(fqn)).thenReturn(TipoResuelto(fqn, schemaDelAviso, 5))
        whenever(schemaRegistryService.getSchemaById(5)).thenReturn(schemaDelAviso)

        notifier.notificar(cambio(breaking = false, previousTopic = null), "com.citypass.movilidad")

        val bytes = argumentCaptor<ByteArray>()
        verify(kafkaTemplate).send(any(), any(), bytes.capture())

        val data = avroService.deserialize(bytes.firstValue)["data"] as Map<*, *>
        assertEquals(false, data["breaking"])
        // Null y no cadena vacía: un consumidor no debería tener que saber que "" quiere
        // decir «no aplica».
        assertNull(data["previousTopic"])
    }

    @Test
    fun `says nothing when the notification type is not registered`() {
        whenever(schemaRegistryService.resolver(fqn)).thenReturn(null)

        notifier.notificar(cambio(breaking = true, previousTopic = "x"), "com.citypass.movilidad")

        verify(kafkaTemplate, never()).send(any(), any(), any())
    }

    @Test
    fun `says nothing when the notification type has no schemaId yet`() {
        whenever(schemaRegistryService.resolver(fqn)).thenReturn(TipoResuelto(fqn, schemaDelAviso, null))

        notifier.notificar(cambio(breaking = true, previousTopic = "x"), "com.citypass.movilidad")

        verify(kafkaTemplate, never()).send(any(), any(), any())
    }

    @Test
    fun `a failure publishing the announcement does not fail the schema change`() {
        whenever(schemaRegistryService.resolver(fqn)).thenReturn(TipoResuelto(fqn, schemaDelAviso, 5))
        whenever(kafkaTemplate.send(any(), any(), any())).thenThrow(RuntimeException("broker caído"))

        // El schema ya cambió: hacer fallar el PUT acá le haría creer al equipo que su
        // cambio no se aplicó cuando sí se aplicó.
        assertDoesNotThrow {
            notifier.notificar(cambio(breaking = true, previousTopic = "x"), "com.citypass.movilidad")
        }
    }
}
