package com.citypass.gateway.controller

import com.citypass.gateway.service.AvroService
import com.citypass.gateway.service.PayloadInvalidoException
import com.citypass.gateway.service.SchemaRegistryService
import com.citypass.gateway.service.TipoResuelto
import com.citypass.gateway.service.TopicAuthorizationService
import org.apache.avro.Schema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.boot.info.BuildProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.security.oauth2.jwt.Jwt
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class EventControllerTest {

    private val kafkaTemplate: KafkaTemplate<String, ByteArray> = mock()
    private val schemaRegistryService: SchemaRegistryService = mock()
    private val avroService: AvroService = mock()
    private val topicAuthorizationService: TopicAuthorizationService = mock()

    private lateinit var controller: EventController

    private val fqn = "com.citypass.test.TestEvent"

    /** Schema con el envelope metadata/data, como los que construye el gateway hoy. */
    private val schema = Schema.Parser().parse("""
    {
      "type": "record",
      "name": "TestEvent",
      "namespace": "com.citypass.test",
      "fields": [
        {"name": "metadata", "type": {
          "type": "record", "name": "EventMetadata", "namespace": "com.citypass.gateway",
          "fields": [
            {"name": "eventId",        "type": "string"},
            {"name": "eventType",      "type": "string"},
            {"name": "receivedAt",     "type": {"type": "long", "logicalType": "timestamp-millis"}},
            {"name": "source",         "type": "string"},
            {"name": "tokenId",        "type": "string"},
            {"name": "schemaId",       "type": "int"},
            {"name": "payloadHash",    "type": "string"},
            {"name": "gatewayVersion", "type": "string"},
            {"name": "instanceId",     "type": "string"}
          ]
        }},
        {"name": "data", "type": {
          "type": "record", "name": "TestEvent", "namespace": "com.citypass.test.data",
          "fields": [{"name": "userId", "type": "string"}]
        }}
      ]
    }
    """.trimIndent())

    /** Schema con el formato plano anterior al envelope. */
    private val legacySchema = Schema.Parser().parse("""
    {
      "type": "record", "name": "Legacy", "namespace": "com.citypass.test",
      "fields": [{"name": "userId", "type": "string"}]
    }
    """.trimIndent())

    @BeforeEach
    fun setUp() {
        val build = BuildProperties(Properties().apply { setProperty("version", "1.2.3") })
        controller = EventController(
            kafkaTemplate, schemaRegistryService, avroService, topicAuthorizationService, build,
            publishTimeoutMs = 5_000
        )
    }

    private fun authorizedJwt(topic: String, subject: String? = "testuser", jti: String? = "tok-1"): Jwt {
        val jwt: Jwt = mock()
        whenever(jwt.subject).thenReturn(subject)
        whenever(jwt.id).thenReturn(jti)
        whenever(topicAuthorizationService.isAllowed(jwt, topic)).thenReturn(true)
        return jwt
    }

    /** Prepara el camino feliz y devuelve el JWT autorizado. */
    private fun readyToPublish(subject: String? = "testuser", jti: String? = "tok-1"): Jwt {
        val jwt = authorizedJwt(fqn, subject, jti)
        whenever(schemaRegistryService.resolver(fqn)).thenReturn(TipoResuelto(fqn, schema, 7))
        whenever(avroService.payloadHash(any(), any())).thenReturn("a".repeat(64))
        whenever(avroService.jsonToAvroBytes(any(), eq(schema), eq(7))).thenReturn(byteArrayOf(1, 2, 3))
        whenever(kafkaTemplate.send(eq(fqn), any<String>(), any()))
            .thenReturn(CompletableFuture.completedFuture(mock()))
        return jwt
    }

    /** El envelope que el controller le pasó a AvroService. */
    private fun capturedEnvelope(): Map<*, *> {
        val captor = argumentCaptor<Map<String, Any>>()
        verify(avroService).jsonToAvroBytes(captor.capture(), any(), any())
        return captor.firstValue
    }

    private fun capturedMetadata(): Map<*, *> = capturedEnvelope()["metadata"] as Map<*, *>

    private fun problemOf(response: ResponseEntity<Any>): ProblemDetail = response.body as ProblemDetail

    // ── autorización ─────────────────────────────────────────────────────────

    @Test
    fun `returns 401 when there is no JWT`() {
        val response = controller.publishEvent(fqn, mapOf("userId" to "u1"), null)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertEquals("Autenticación requerida", problemOf(response).title)
    }

    @Test
    fun `returns 403 when the topic is not allowed`() {
        val jwt: Jwt = mock()
        whenever(jwt.subject).thenReturn("testuser")
        whenever(topicAuthorizationService.isAllowed(jwt, fqn)).thenReturn(false)

        val response = controller.publishEvent(fqn, mapOf("userId" to "u1"), jwt)

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertTrue(problemOf(response).detail!!.contains("testuser"))
    }

    // ── resolución del event type ────────────────────────────────────────────

    @Test
    fun `returns 404 when the event type is unknown`() {
        val unknown = "com.citypass.test.Unknown"
        val jwt = authorizedJwt(unknown)
        whenever(schemaRegistryService.resolver(unknown)).thenReturn(null)
        whenever(schemaRegistryService.getAvailableEventTypes()).thenReturn(setOf(fqn))

        val response = controller.publishEvent(unknown, mapOf("a" to "b"), jwt)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals(setOf(fqn), problemOf(response).properties!!["availableEventTypes"])
    }

    @Test
    fun `publishes on the current major version, not on the logical name`() {
        val jwt = authorizedJwt(fqn)
        // El productor manda el nombre lógico; el gateway rutea al tópico vigente.
        whenever(schemaRegistryService.resolver(fqn)).thenReturn(TipoResuelto("$fqn.v2", schema, 7))

        val response = controller.publishEvent(fqn, mapOf("userId" to "u1"), jwt)

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        verify(kafkaTemplate).send(eq("$fqn.v2"), any(), anyOrNull())
    }

    @Test
    fun `returns 503 when the schema is not registered yet`() {
        val jwt = authorizedJwt(fqn)
        whenever(schemaRegistryService.resolver(fqn)).thenReturn(TipoResuelto(fqn, schema, null))

        val response = controller.publishEvent(fqn, mapOf("userId" to "u1"), jwt)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
    }

    @Test
    fun `returns 400 for a legacy flat schema`() {
        val jwt = authorizedJwt(fqn)
        whenever(schemaRegistryService.resolver(fqn)).thenReturn(TipoResuelto(fqn, legacySchema, 3))

        val response = controller.publishEvent(fqn, mapOf("userId" to "u1"), jwt)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertTrue(problemOf(response).detail!!.contains("campos planos"))
    }

    // ── el envelope ──────────────────────────────────────────────────────────

    @Test
    fun `returns 202 and separates metadata from data`() {
        val jwt = readyToPublish()

        val response = controller.publishEvent(fqn, mapOf("userId" to "u123"), jwt)

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)

        val envelope = capturedEnvelope()
        assertEquals(setOf("metadata", "data"), envelope.keys)
        assertEquals(mapOf("userId" to "u123"), envelope["data"])
    }

    @Test
    fun `fills every metadata field from the gateway`() {
        val jwt = readyToPublish()

        controller.publishEvent(fqn, mapOf("userId" to "u123"), jwt)

        val metadata = capturedMetadata()
        assertEquals(fqn, metadata["eventType"])
        assertEquals("testuser", metadata["source"])
        assertEquals("tok-1", metadata["tokenId"])
        assertEquals(7, metadata["schemaId"])
        assertEquals("a".repeat(64), metadata["payloadHash"])
        assertEquals("1.2.3", metadata["gatewayVersion"])
        assertNotNull(metadata["eventId"])
        assertTrue(metadata["receivedAt"] is Long)
        assertTrue((metadata["instanceId"] as String).startsWith("gw-"))
    }

    @Test
    fun `data cannot forge metadata fields`() {
        val jwt = readyToPublish()

        // El body es directamente el payload de negocio: aunque traiga nombres de
        // metadata, van a parar a `data` y no pueden alcanzar el record de auditoría.
        controller.publishEvent(
            fqn, mapOf("userId" to "u123", "source" to "grupo7", "eventId" to "falso"), jwt
        )

        val metadata = capturedMetadata()
        assertEquals("testuser", metadata["source"], "source debe venir del JWT, no del request")
        assertNotEquals("falso", metadata["eventId"])

        val data = capturedEnvelope()["data"] as Map<*, *>
        assertEquals("grupo7", data["source"], "lo del productor se aísla, no se pierde")
    }

    @Test
    fun `hashes the business payload against the data schema`() {
        val jwt = readyToPublish()

        controller.publishEvent(fqn, mapOf("userId" to "u123"), jwt)

        verify(avroService).payloadHash(
            eq(mapOf("userId" to "u123")),
            eq(schema.getField("data").schema())
        )
    }

    @Test
    fun `falls back to unknown when the JWT has no subject or jti`() {
        val jwt = readyToPublish(subject = null, jti = null)

        controller.publishEvent(fqn, mapOf("userId" to "u123"), jwt)

        val metadata = capturedMetadata()
        assertEquals("unknown", metadata["source"])
        assertEquals("unknown", metadata["tokenId"])
    }

    // ── publicación en Kafka ─────────────────────────────────────────────────

    @Test
    fun `uses userId as the Kafka key`() {
        val jwt = readyToPublish()

        controller.publishEvent(fqn, mapOf("userId" to "u123"), jwt)

        verify(kafkaTemplate).send(eq(fqn), eq("u123"), any())
    }

    @Test
    fun `falls back to eventId as the Kafka key when data has no userId`() {
        val jwt = readyToPublish()

        val response = controller.publishEvent(fqn, mapOf(), jwt)

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        val metadata = (response.body as Map<*, *>)["metadata"] as Map<*, *>
        verify(kafkaTemplate).send(eq(fqn), eq(metadata["eventId"] as String), any())
    }

    @Test
    fun `returns 504 when Kafka does not confirm in time`() {
        val jwt = readyToPublish()
        val colgado = mock<CompletableFuture<SendResult<String, ByteArray>>>()
        whenever(colgado.get(any(), any())).thenThrow(TimeoutException())
        whenever(kafkaTemplate.send(eq(fqn), any<String>(), any())).thenReturn(colgado)

        val response = controller.publishEvent(fqn, mapOf("userId" to "u123"), jwt)

        // Sin tope, el hilo de request esperaría para siempre y el pool se agotaría.
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.statusCode)
        assertEquals("Kafka no respondió a tiempo", problemOf(response).title)
    }

    @Test
    fun `waits for the confirmation with the configured timeout`() {
        val jwt = readyToPublish()
        val futuro = mock<CompletableFuture<SendResult<String, ByteArray>>>()
        whenever(futuro.get(any(), any())).thenReturn(mock())
        whenever(kafkaTemplate.send(eq(fqn), any<String>(), any())).thenReturn(futuro)

        controller.publishEvent(fqn, mapOf("userId" to "u123"), jwt)

        verify(futuro).get(5_000L, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `returns 502 when Kafka fails`() {
        val jwt = readyToPublish()
        whenever(kafkaTemplate.send(eq(fqn), any<String>(), any()))
            .thenThrow(RuntimeException("Kafka broker unavailable"))

        val response = controller.publishEvent(fqn, mapOf("userId" to "u123"), jwt)

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        assertTrue(problemOf(response).detail!!.contains("Kafka broker unavailable"))
    }

    @Test
    fun `reports a generic detail when the failure has no message`() {
        val jwt = readyToPublish()
        whenever(avroService.jsonToAvroBytes(any(), eq(schema), eq(7))).thenThrow(RuntimeException())

        val response = controller.publishEvent(fqn, mapOf("userId" to "u123"), jwt)

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        assertEquals("No se pudo publicar el evento.", problemOf(response).detail)
    }

    @Test
    fun `la respuesta devuelve el envelope completo, no un resumen`() {
        // Quien publica no puede calcular por su cuenta lo que el gateway le estampó, así
        // que si la respuesta recorta la metadata, la única forma de ver qué se publicó a
        // su nombre es leerlo de Kafka.
        val jwt = readyToPublish()

        val response = controller.publishEvent(fqn, mapOf("userId" to "u123"), jwt)

        val body = response.body as Map<*, *>
        assertEquals(setOf("metadata", "data"), body.keys)
        assertEquals(mapOf("userId" to "u123"), body["data"])

        val metadata = body["metadata"] as Map<*, *>
        assertEquals(
            setOf(
                "eventId", "eventType", "receivedAt", "source", "tokenId",
                "schemaId", "payloadHash", "gatewayVersion", "instanceId"
            ),
            metadata.keys
        )
        assertEquals(fqn, metadata["eventType"])
    }

    @Test
    fun `un payload que no cumple el schema devuelve 400 y nombra el campo`() {
        // Antes escapaba como ClassCastException sin capturar: un 500 con el cuerpo de
        // error por defecto de Spring, sin `detail` y sin decir qué campo estaba mal.
        val jwt = readyToPublish()
        // El payloadHash ya convierte el payload contra el schema, así que es donde falla
        // primero un campo con el tipo equivocado.
        whenever(avroService.payloadHash(any(), any()))
            .thenThrow(PayloadInvalidoException("userId", "esperaba string y recibió Integer."))

        val response = controller.publishEvent(fqn, mapOf("userId" to 42), jwt)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("El evento no cumple el schema", problemOf(response).title)
        assertTrue(
            problemOf(response).detail!!.contains("userId"),
            "el detalle tiene que nombrar el campo: ${problemOf(response).detail}"
        )
        // Y no llega a publicarse.
        verifyNoInteractions(kafkaTemplate)
    }
}
