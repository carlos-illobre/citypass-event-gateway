package com.citypass.gateway.controller

import com.citypass.gateway.model.Subscription
import com.citypass.gateway.service.CambioDeEsquema
import com.citypass.gateway.service.CupoAgotadoException
import com.citypass.gateway.service.SchemaChangeNotifier
import com.citypass.gateway.service.SchemaRegistryService
import com.citypass.gateway.service.SubscriptionService
import com.citypass.gateway.service.TipoResuelto
import com.citypass.gateway.service.TopicAuthorizationService
import org.apache.avro.Schema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.jwt.Jwt

class SchemaControllerTest {

    private val schemaRegistryService: SchemaRegistryService = mock()
    private val subscriptionService: SubscriptionService = mock()
    private val topicAuthorizationService: TopicAuthorizationService = mock()
    private val schemaChangeNotifier: SchemaChangeNotifier = mock()
    private lateinit var controller: SchemaController

    private val fqn = "com.citypass.test.TestEvent"

    private val schema = Schema.Parser().parse("""
    {
      "type": "record",
      "name": "TestEvent",
      "namespace": "com.citypass.test",
      "doc": "Con acentos: publicación de una bicicleta liberada.",
      "fields": [{"name": "userId", "type": "string"}]
    }
    """.trimIndent())

    private fun jwtWithNamespace(namespace: String): Jwt {
        val jwt: Jwt = mock()
        whenever(jwt.claims).thenReturn(mapOf("namespace" to namespace))
        return jwt
    }

    private fun problemOf(response: ResponseEntity<Any>): ProblemDetail = response.body as ProblemDetail

    @BeforeEach
    fun setUp() {
        controller = SchemaController(
            schemaRegistryService, subscriptionService, topicAuthorizationService, schemaChangeNotifier
        )
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    fun `list returns the summaries from the service`() {
        val summaries = listOf(mapOf<String, Any?>("fqn" to fqn, "schemaId" to 3))
        whenever(schemaRegistryService.listEventTypes(null)).thenReturn(summaries)

        val response = controller.list(null)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(summaries, response.body)
    }

    @Test
    fun `list forwards the namespace filter`() {
        whenever(schemaRegistryService.listEventTypes("com.citypass.test")).thenReturn(emptyList())

        val response = controller.list("com.citypass.test")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(emptyList<Any>(), response.body)
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    fun `get returns 404 when the event type does not exist`() {
        whenever(schemaRegistryService.resolver("com.citypass.test.Unknown")).thenReturn(null)

        val response = controller.get("com.citypass.test.Unknown")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("Event type no encontrado", problemOf(response).title)
    }

    @Test
    fun `get returns the schema as a JSON object, not a string`() {
        whenever(schemaRegistryService.resolver(fqn)).thenReturn(TipoResuelto(fqn, schema, 3))

        val response = controller.get(fqn)

        assertEquals(HttpStatus.OK, response.statusCode)
        // Devolverlo como String hacía que Spring lo escribiera como text/plain
        // en ISO-8859-1, rompiendo los acentos de los `doc`.
        val body = response.body as Map<*, *>
        assertEquals("TestEvent", body["name"])
        assertEquals("com.citypass.test", body["namespace"])
        assertTrue((body["doc"] as String).contains("publicación"))
    }

    // ── register ──────────────────────────────────────────────────────────────

    @Test
    fun `register returns 400 when name is missing`() {
        val response = controller.register(mapOf("fields" to emptyList<Any>()), null)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `register returns 401 when there is no JWT`() {
        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, null)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `register returns 400 when the JWT has no namespace claim`() {
        val jwt: Jwt = mock()
        whenever(jwt.claims).thenReturn(emptyMap())

        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, jwt)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Token sin namespace", problemOf(response).title)
    }

    @Test
    fun `register returns 400 when fields is missing`() {
        val jwt = jwtWithNamespace("com.citypass.test")
        val response = controller.register(mapOf("name" to "TestEvent"), jwt)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `register returns 201 with the FQN and a Location header`() {
        val jwt = jwtWithNamespace("com.citypass.test")
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any())).thenReturn(Result.success(5))

        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, jwt)

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertEquals("/api/v1/event-types/$fqn", response.headers.location.toString())

        val body = response.body as Map<*, *>
        assertEquals(fqn, body["fqn"])
        assertEquals("com.citypass.test", body["namespace"])
        assertEquals("TestEvent", body["name"])
        assertEquals(5, body["schemaId"])
    }

    @Test
    fun `register returns 400 when the event type is invalid`() {
        val jwt = jwtWithNamespace("com.citypass.test")
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any()))
            .thenReturn(Result.failure(IllegalArgumentException("El name es inválido")))

        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, jwt)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("El name es inválido", problemOf(response).detail)
    }

    @Test
    fun `register returns 502 when the Schema Registry fails`() {
        val jwt = jwtWithNamespace("com.citypass.test")
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("Schema Registry unavailable")))

        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, jwt)

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        assertEquals("Error del Schema Registry", problemOf(response).title)
    }

    @Test
    fun `register tolerates a validation failure without a message`() {
        val jwt = jwtWithNamespace("com.citypass.test")
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any()))
            .thenReturn(Result.failure(IllegalArgumentException()))

        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, jwt)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("", problemOf(response).detail)
    }

    @Test
    fun `register tolerates a registry failure without a message`() {
        val jwt = jwtWithNamespace("com.citypass.test")
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException()))

        val request = mapOf<String, Any>("name" to "TestEvent", "fields" to emptyList<Any>())
        val response = controller.register(request, jwt)

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        assertEquals("", problemOf(response).detail)
    }

    // ── update (PUT) ──────────────────────────────────────────────────────────

    private fun autorizado(namespace: String = "com.citypass.test"): Jwt {
        val jwt = jwtWithNamespace(namespace)
        whenever(topicAuthorizationService.isAllowed(jwt, fqn)).thenReturn(true)
        return jwt
    }

    private fun cambio(
        breaking: Boolean = false,
        previousTopic: String? = null,
        unchanged: Boolean = false,
        topic: String = fqn,
        version: Int = 1
    ) = CambioDeEsquema(fqn, topic, version, 9, breaking, previousTopic, unchanged)

    @Test
    fun `update returns 401 without a token`() {
        val response = controller.update(fqn, mapOf("fields" to emptyList<Any>()), null)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `update returns 400 when the token has no namespace`() {
        val jwt: Jwt = mock()
        whenever(jwt.claims).thenReturn(emptyMap())

        val response = controller.update(fqn, mapOf("fields" to emptyList<Any>()), jwt)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Token sin namespace", problemOf(response).title)
    }

    @Test
    fun `update returns 403 for an event type of another team`() {
        val jwt = jwtWithNamespace("com.citypass.otros")
        whenever(topicAuthorizationService.isAllowed(jwt, fqn)).thenReturn(false)

        val response = controller.update(fqn, mapOf("fields" to emptyList<Any>()), jwt)

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertTrue(problemOf(response).detail!!.contains("Sólo el equipo dueño"))
    }

    @Test
    fun `update returns 400 when fields is missing`() {
        val response = controller.update(fqn, emptyMap(), autorizado())

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Falta un campo requerido", problemOf(response).title)
    }

    @Test
    fun `update passes the name derived from the FQN, never a new one`() {
        whenever(schemaRegistryService.updateSchema(any(), any(), any()))
            .thenReturn(Result.success(cambio()))

        controller.update(fqn, mapOf("fields" to emptyList<Any>(), "name" to "OtroNombre"), autorizado())

        // El name sale del FQN de la ruta: mandar otro en el body no renombra nada.
        verify(schemaRegistryService).updateSchema("com.citypass.test", "TestEvent", emptyList())
    }

    @Test
    fun `a compatible change keeps the topic and reports breaking false`() {
        whenever(schemaRegistryService.updateSchema(any(), any(), any()))
            .thenReturn(Result.success(cambio()))

        val response = controller.update(fqn, mapOf("fields" to emptyList<Any>()), autorizado())

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body as Map<*, *>
        assertEquals(fqn, body["topic"])
        assertEquals(false, body["breaking"])
        assertNull(body["previousTopic"])
        assertNull(body["subscriptionsOnPreviousVersion"], "no hay versión anterior que contar")
    }

    @Test
    fun `a breaking change reports how many subscriptions stayed behind`() {
        whenever(schemaRegistryService.updateSchema(any(), any(), any()))
            .thenReturn(Result.success(cambio(breaking = true, previousTopic = fqn, topic = "$fqn.v2", version = 2)))
        whenever(subscriptionService.suscriptoresA(listOf(fqn))).thenReturn(listOf(
            Subscription(topic = fqn, callbackUrl = "http://a", owner = "com.citypass.otros"),
            Subscription(topic = fqn, callbackUrl = "http://b", owner = "com.citypass.otros")
        ))

        val response = controller.update(fqn, mapOf("fields" to emptyList<Any>()), autorizado())

        val body = response.body as Map<*, *>
        assertEquals("$fqn.v2", body["topic"])
        assertEquals(2, body["version"])
        assertEquals(true, body["breaking"])
        assertEquals(fqn, body["previousTopic"])
        // Es lo que convierte la ruptura en algo consciente: se ve a cuántos dejó atrás.
        assertEquals(2, body["subscriptionsOnPreviousVersion"])
    }

    @Test
    fun `a change is announced on the bus`() {
        val cambio = cambio(breaking = true, previousTopic = fqn, topic = "$fqn.v2", version = 2)
        whenever(schemaRegistryService.updateSchema(any(), any(), any())).thenReturn(Result.success(cambio))

        controller.update(fqn, mapOf("fields" to emptyList<Any>()), autorizado())

        verify(schemaChangeNotifier).notificar(cambio, "com.citypass.test")
    }

    @Test
    fun `an unchanged schema is not announced`() {
        whenever(schemaRegistryService.updateSchema(any(), any(), any()))
            .thenReturn(Result.success(cambio(unchanged = true)))

        val response = controller.update(fqn, mapOf("fields" to emptyList<Any>()), autorizado())

        assertEquals(true, (response.body as Map<*, *>)["unchanged"])
        // Avisar de un cambio que no ocurrió haría que los consumidores revisaran de gusto.
        verify(schemaChangeNotifier, never()).notificar(any(), any())
    }

    @Test
    fun `update returns 404 when the event type does not exist`() {
        whenever(schemaRegistryService.updateSchema(any(), any(), any()))
            .thenReturn(Result.failure(NoSuchElementException("No existe")))

        val response = controller.update(fqn, mapOf("fields" to emptyList<Any>()), autorizado())

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("No existe", problemOf(response).detail)
    }

    @Test
    fun `update returns 400 for an invalid schema`() {
        whenever(schemaRegistryService.updateSchema(any(), any(), any()))
            .thenReturn(Result.failure(IllegalArgumentException("Schema Avro inválido")))

        val response = controller.update(fqn, mapOf("fields" to emptyList<Any>()), autorizado())

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `update returns 502 when the registry cannot decide`() {
        whenever(schemaRegistryService.updateSchema(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("registry caído")))

        val response = controller.update(fqn, mapOf("fields" to emptyList<Any>()), autorizado())

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        assertEquals("registry caído", problemOf(response).detail)
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    fun `delete returns 401 without a token`() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.delete(fqn, null).statusCode)
    }

    @Test
    fun `delete returns 400 when the token has no namespace`() {
        val jwt: Jwt = mock()
        whenever(jwt.claims).thenReturn(emptyMap())

        assertEquals(HttpStatus.BAD_REQUEST, controller.delete(fqn, jwt).statusCode)
    }

    @Test
    fun `delete returns 403 for an event type of another team`() {
        val jwt = jwtWithNamespace("com.citypass.otros")
        whenever(topicAuthorizationService.isAllowed(jwt, fqn)).thenReturn(false)

        assertEquals(HttpStatus.FORBIDDEN, controller.delete(fqn, jwt).statusCode)
    }

    @Test
    fun `delete removes every version and its own subscriptions`() {
        whenever(schemaRegistryService.topicosDeEventType(fqn)).thenReturn(listOf(fqn, "$fqn.v2"))
        whenever(subscriptionService.suscriptoresA(listOf(fqn, "$fqn.v2"))).thenReturn(listOf(
            Subscription(topic = fqn, callbackUrl = "http://a", owner = "com.citypass.test")
        ))
        whenever(schemaRegistryService.deleteEventType(fqn))
            .thenReturn(Result.success(listOf(fqn, "$fqn.v2")))
        whenever(subscriptionService.unregisterTopics(listOf(fqn, "$fqn.v2"))).thenReturn(1)

        val response = controller.delete(fqn, autorizado())

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body as Map<*, *>
        assertEquals(listOf(fqn, "$fqn.v2"), body["deletedTopics"])
        assertEquals(1, body["subscriptionsRemoved"])
    }

    @Test
    fun `delete is refused while another team is subscribed`() {
        whenever(schemaRegistryService.topicosDeEventType(fqn)).thenReturn(listOf(fqn))
        whenever(subscriptionService.suscriptoresA(listOf(fqn))).thenReturn(listOf(
            Subscription(topic = fqn, callbackUrl = "http://a", owner = "com.citypass.otros")
        ))

        val response = controller.delete(fqn, autorizado())

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("Hay equipos suscriptos", problemOf(response).title)
        // Se nombra al suscriptor: sin eso no hay con quién coordinar la baja.
        val suscriptores = problemOf(response).properties!!["subscribers"] as List<*>
        assertEquals("com.citypass.otros", (suscriptores[0] as Map<*, *>)["owner"])
        // Y no se borró nada.
        verify(schemaRegistryService, never()).deleteEventType(any())
    }

    @Test
    fun `delete returns 404 when the event type does not exist`() {
        whenever(schemaRegistryService.topicosDeEventType(fqn)).thenReturn(emptyList())
        whenever(schemaRegistryService.deleteEventType(fqn))
            .thenReturn(Result.failure(NoSuchElementException("No existe")))

        assertEquals(HttpStatus.NOT_FOUND, controller.delete(fqn, autorizado()).statusCode)
    }

    @Test
    fun `delete returns 502 when the deletion could not be completed`() {
        whenever(schemaRegistryService.topicosDeEventType(fqn)).thenReturn(emptyList())
        whenever(schemaRegistryService.deleteEventType(fqn))
            .thenReturn(Result.failure(RuntimeException("registry caído")))

        assertEquals(HttpStatus.BAD_GATEWAY, controller.delete(fqn, autorizado()).statusCode)
    }

    // ── deleteVersion ─────────────────────────────────────────────────────────

    @Test
    fun `deleteVersion returns 401 without a token`() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.deleteVersion(fqn, 1, null).statusCode)
    }

    @Test
    fun `deleteVersion returns 400 when the token has no namespace`() {
        val jwt: Jwt = mock()
        whenever(jwt.claims).thenReturn(emptyMap())

        assertEquals(HttpStatus.BAD_REQUEST, controller.deleteVersion(fqn, 1, jwt).statusCode)
    }

    @Test
    fun `deleteVersion returns 403 for an event type of another team`() {
        val jwt = jwtWithNamespace("com.citypass.otros")
        whenever(topicAuthorizationService.isAllowed(jwt, fqn)).thenReturn(false)

        assertEquals(HttpStatus.FORBIDDEN, controller.deleteVersion(fqn, 1, jwt).statusCode)
    }

    @Test
    fun `deleteVersion retires one old version`() {
        whenever(schemaRegistryService.topicoDe(fqn, 1)).thenReturn(fqn)
        whenever(subscriptionService.suscriptoresA(listOf(fqn))).thenReturn(emptyList())
        whenever(schemaRegistryService.deleteVersion(fqn, 1)).thenReturn(Result.success(fqn))
        whenever(subscriptionService.unregisterTopics(listOf(fqn))).thenReturn(0)

        val response = controller.deleteVersion(fqn, 1, autorizado())

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(listOf(fqn), (response.body as Map<*, *>)["deletedTopics"])
    }

    @Test
    fun `deleteVersion is refused while another team is subscribed`() {
        whenever(schemaRegistryService.topicoDe(fqn, 1)).thenReturn(fqn)
        whenever(subscriptionService.suscriptoresA(listOf(fqn))).thenReturn(listOf(
            Subscription(topic = fqn, callbackUrl = "http://a", owner = "com.citypass.otros")
        ))

        assertEquals(HttpStatus.CONFLICT, controller.deleteVersion(fqn, 1, autorizado()).statusCode)
    }

    @Test
    fun `deleteVersion returns 400 for the current version`() {
        whenever(schemaRegistryService.topicoDe(fqn, 2)).thenReturn("$fqn.v2")
        whenever(schemaRegistryService.deleteVersion(fqn, 2))
            .thenReturn(Result.failure(IllegalStateException("es la vigente")))

        val response = controller.deleteVersion(fqn, 2, autorizado())

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("No se puede borrar esa versión", problemOf(response).title)
    }

    @Test
    fun `deleteVersion returns 404 for a version that does not exist`() {
        whenever(schemaRegistryService.topicoDe(fqn, 9)).thenReturn("$fqn.v9")
        whenever(schemaRegistryService.deleteVersion(fqn, 9))
            .thenReturn(Result.failure(NoSuchElementException("no existe")))

        assertEquals(HttpStatus.NOT_FOUND, controller.deleteVersion(fqn, 9, autorizado()).statusCode)
    }

    @Test
    fun `deleteVersion returns 502 when the deletion could not be completed`() {
        whenever(schemaRegistryService.topicoDe(fqn, 1)).thenReturn(fqn)
        whenever(schemaRegistryService.deleteVersion(fqn, 1))
            .thenReturn(Result.failure(RuntimeException("broker caído")))

        assertEquals(HttpStatus.BAD_GATEWAY, controller.deleteVersion(fqn, 1, autorizado()).statusCode)
    }

    // ── backup ────────────────────────────────────────────────────────────────

    @Test
    fun `export returns the backup document of my namespace`() {
        val documento = mapOf<String, Any?>(
            "formatVersion" to 1,
            "namespace" to "com.citypass.test",
            "exportedAt" to "2026-08-18T04:12:33.481Z",
            "eventTypes" to listOf(mapOf("name" to "TestEvent"))
        )
        whenever(schemaRegistryService.exportarNamespace("com.citypass.test")).thenReturn(documento)

        val response = controller.export(jwtWithNamespace("com.citypass.test"))

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(documento, response.body)
    }

    /** El namespace sale del token y no de un parámetro: nadie exporta lo ajeno. */
    @Test
    fun `export only asks for the namespace in the token`() {
        whenever(schemaRegistryService.exportarNamespace(any())).thenReturn(emptyMap())

        controller.export(jwtWithNamespace("com.citypass.otro"))

        verify(schemaRegistryService).exportarNamespace("com.citypass.otro")
    }

    @Test
    fun `export returns 401 without a token`() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.export(null).statusCode)
    }

    @Test
    fun `export returns 400 when the token has no namespace`() {
        val jwt: Jwt = mock()
        whenever(jwt.claims).thenReturn(emptyMap())

        val response = controller.export(jwt)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Token sin namespace", problemOf(response).title)
    }

    // ── cupo ──────────────────────────────────────────────────────────────────

    @Test
    fun `quota returns the namespace usage`() {
        val cupo = mapOf<String, Any>("namespace" to "com.citypass.test", "used" to 3, "limit" to 25, "remaining" to 22)
        whenever(schemaRegistryService.cupoDe("com.citypass.test")).thenReturn(cupo)

        val response = controller.quota(jwtWithNamespace("com.citypass.test"))

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(cupo, response.body)
    }

    @Test
    fun `quota returns 401 without a token`() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.quota(null).statusCode)
    }

    @Test
    fun `quota returns 400 when the token has no namespace`() {
        val jwt: Jwt = mock()
        whenever(jwt.claims).thenReturn(emptyMap())

        val response = controller.quota(jwt)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Token sin namespace", problemOf(response).title)
    }

    @Test
    fun `register returns 409 when the namespace ran out of quota`() {
        val jwt = jwtWithNamespace("com.citypass.test")
        whenever(schemaRegistryService.registerNewSchema(any(), any(), any()))
            .thenReturn(Result.failure(CupoAgotadoException("ya tiene 25 event types, que es el máximo")))

        val response = controller.register(
            mapOf("name" to "TestEvent", "fields" to emptyList<Any>()), jwt
        )

        // 409 y no 400: el pedido está bien, lo que falta es lugar. Se arregla borrando
        // algo, no corrigiendo el request, y el código tiene que decir cuál de las dos.
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("Cupo de event types agotado", problemOf(response).title)
        assertTrue(problemOf(response).detail!!.contains("máximo"))
    }
}
