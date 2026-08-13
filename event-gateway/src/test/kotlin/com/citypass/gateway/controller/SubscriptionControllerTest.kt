package com.citypass.gateway.controller

import com.citypass.gateway.model.Subscription
import com.citypass.gateway.service.CallbackUrlValidator
import com.citypass.gateway.service.SubscriptionService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.jwt.Jwt

class SubscriptionControllerTest {

    private val subscriptionService: SubscriptionService = mock()

    // Acepta cualquier destino salvo donde el test diga lo contrario: la lógica del
    // validador se prueba aparte, en CallbackUrlValidatorTest.
    private val callbackUrlValidator: CallbackUrlValidator = mock()

    private lateinit var controller: SubscriptionController

    private val movilidad = "com.citypass.movilidad"
    private val reclamos  = "com.citypass.reclamos"

    @BeforeEach
    fun setUp() {
        controller = SubscriptionController(subscriptionService, callbackUrlValidator)
    }

    /** Un token de grupo. Sin `namespace` cuando se pasa null. */
    private fun jwtOf(namespace: String?, subject: String? = "usuario1"): Jwt {
        val jwt: Jwt = mock()
        whenever(jwt.claims).thenReturn(
            if (namespace == null) emptyMap() else mapOf("namespace" to namespace)
        )
        whenever(jwt.subject).thenReturn(subject)
        return jwt
    }

    private fun problemOf(response: ResponseEntity<Any>): ProblemDetail = response.body as ProblemDetail

    // ── register ──────────────────────────────────────────────────────────────

    @Test
    fun `register returns 400 when topic is missing`() {
        val response = controller.register(mapOf("callbackUrl" to "http://localhost/hook"), jwtOf(movilidad))
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `register returns 400 when callbackUrl is missing`() {
        val response = controller.register(mapOf("topic" to "test.topic"), jwtOf(movilidad))
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `register returns 400 when the token has no namespace`() {
        val response = controller.register(mapOf("topic" to "t", "callbackUrl" to "http://c"), jwtOf(null))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Token sin namespace", problemOf(response).title)
    }

    @Test
    fun `register stores the group as owner and the user who created it`() {
        val sub = Subscription(
            id = "s-1", topic = "test.topic", callbackUrl = "http://localhost/hook",
            owner = movilidad, createdBy = "usuario1"
        )
        whenever(subscriptionService.register("test.topic", "http://localhost/hook", movilidad, "usuario1"))
            .thenReturn(sub)

        val request = mapOf("topic" to "test.topic", "callbackUrl" to "http://localhost/hook")
        val response = controller.register(request, jwtOf(movilidad))

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertEquals(sub, response.body)
    }

    @Test
    fun `register falls back to unknown when the token has no subject`() {
        val sub = Subscription(topic = "t", callbackUrl = "http://c", owner = movilidad, createdBy = "unknown")
        whenever(subscriptionService.register("t", "http://c", movilidad, "unknown")).thenReturn(sub)

        val response = controller.register(
            mapOf("topic" to "t", "callbackUrl" to "http://c"),
            jwtOf(movilidad, subject = null)
        )

        assertEquals(HttpStatus.CREATED, response.statusCode)
    }

    @Test
    fun `register returns 400 when the callbackUrl points to the internal network`() {
        whenever(callbackUrlValidator.reject("http://169.254.169.254/hook"))
            .thenReturn("'169.254.169.254' es una dirección de red interna.")

        val response = controller.register(
            mapOf("topic" to "t", "callbackUrl" to "http://169.254.169.254/hook"),
            jwtOf(movilidad)
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("callbackUrl inválida", problemOf(response).title)
        // La suscripción no se crea: si se creara, cada evento del tópico dispararía un
        // POST contra esa dirección hasta que alguien la diera de baja.
        verify(subscriptionService, never()).register(any(), any(), any(), any())
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    fun `list only returns the subscriptions of the requesting group`() {
        val propia = Subscription(topic = "t1", callbackUrl = "http://c1", owner = movilidad)
        whenever(subscriptionService.getAll(movilidad)).thenReturn(listOf(propia))

        val response = controller.list(null, jwtOf(movilidad))

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(listOf(propia), response.body)
        // Listar las ajenas expondría las URLs internas de otros equipos y los ids
        // con los que darlas de baja.
        verify(subscriptionService, never()).getAll(eq(reclamos))
    }

    @Test
    fun `list filters by topic`() {
        val uno = Subscription(topic = "t1", callbackUrl = "http://c1", owner = movilidad)
        val dos = Subscription(topic = "t2", callbackUrl = "http://c2", owner = movilidad)
        whenever(subscriptionService.getAll(movilidad)).thenReturn(listOf(uno, dos))

        val response = controller.list("t2", jwtOf(movilidad))

        assertEquals(listOf(dos), response.body)
    }

    @Test
    fun `list returns 400 when the token has no namespace`() {
        val response = controller.list(null, jwtOf(null))
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    // ── unregister ────────────────────────────────────────────────────────────

    @Test
    fun `unregister returns 204 for an own subscription`() {
        whenever(subscriptionService.unregister("s-1", movilidad)).thenReturn(true)

        val response = controller.unregister("s-1", jwtOf(movilidad))
        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
    }

    @Test
    fun `a group cannot delete the subscription of another group`() {
        // El servicio devuelve false porque el dueño no coincide, y la respuesta es la
        // misma que para una inexistente: confirmar que existe filtraría ids ajenos.
        whenever(subscriptionService.unregister("s-de-otro", movilidad)).thenReturn(false)

        val response = controller.unregister("s-de-otro", jwtOf(movilidad))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("Suscripción no encontrada", problemOf(response).title)
    }

    @Test
    fun `unregister returns 400 when the token has no namespace`() {
        val response = controller.unregister("s-1", jwtOf(null))
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }
}
