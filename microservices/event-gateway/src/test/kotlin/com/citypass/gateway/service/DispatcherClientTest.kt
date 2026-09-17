package com.citypass.gateway.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class DispatcherClientTest {

    private val url = "http://webhook-dispatcher:8085"
    private lateinit var server: MockRestServiceServer
    private lateinit var client: DispatcherClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        client = DispatcherClient(builder.build(), url)
    }

    private fun consulta(topicos: String) = "$url/internal/suscripciones?topics=$topicos"

    // ── con dispatcher desplegado ─────────────────────────────────────────────

    @Test
    fun `informa los suscriptores de un topico`() {
        server.expect(requestTo(consulta("t1")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                """[{"owner":"com.citypass.reclamos","topic":"t1"}]""", MediaType.APPLICATION_JSON))

        assertEquals(listOf(Suscriptor("com.citypass.reclamos", "t1")), client.suscriptoresA(listOf("t1")))
    }

    @Test
    fun `una lista vacia de suscriptores es una respuesta valida`() {
        server.expect(requestTo(consulta("t1"))).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))
        assertEquals(emptyList<Suscriptor>(), client.suscriptoresA(listOf("t1")))
    }

    @Test
    fun `un cuerpo vacio se lee como sin suscriptores`() {
        server.expect(requestTo(consulta("t1"))).andRespond(withSuccess())
        assertEquals(emptyList<Suscriptor>(), client.suscriptoresA(listOf("t1")))
    }

    /** Un `null` explícito en el cuerpo, que no es lo mismo que una respuesta sin cuerpo. */
    @Test
    fun `un cuerpo nulo se lee como sin suscriptores`() {
        server.expect(requestTo(consulta("t1")))
            .andRespond(withSuccess("null", MediaType.APPLICATION_JSON))
        assertEquals(emptyList<Suscriptor>(), client.suscriptoresA(listOf("t1")))
    }

    /**
     * La distinción que sostiene el rechazo del borrado: «no hay suscriptores» y «no sé si
     * hay suscriptores» tienen que poder diferenciarse, o el gateway borraría un event type
     * que otros equipos están recibiendo cada vez que el dispatcher no responda.
     */
    @Test
    fun `si el dispatcher no responde devuelve null, no una lista vacia`() {
        server.expect(requestTo(consulta("t1"))).andRespond(withServerError())
        assertNull(client.suscriptoresA(listOf("t1")))
    }

    @Test
    fun `da de baja las suscripciones de varios topicos`() {
        server.expect(requestTo(consulta("t1,t2")))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withSuccess("""{"removed":3}""", MediaType.APPLICATION_JSON))

        assertEquals(3, client.borrarSuscripcionesDe(listOf("t1", "t2")))
    }

    @Test
    fun `una respuesta sin el campo removed se lee como cero`() {
        server.expect(requestTo(consulta("t1"))).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
        assertEquals(0, client.borrarSuscripcionesDe(listOf("t1")))
    }

    @Test
    fun `si la baja falla devuelve null`() {
        server.expect(requestTo(consulta("t1"))).andRespond(withServerError())
        assertNull(client.borrarSuscripcionesDe(listOf("t1")))
    }

    // ── sin dispatcher desplegado ─────────────────────────────────────────────

    /**
     * Con los webhooks apagados no hay suscripciones: la lista vacía y el cero no son una
     * suposición optimista sino la respuesta correcta, y por eso no llaman a nadie.
     */
    @Test
    fun `sin dispatcher configurado no consulta y responde vacio`() {
        val sinDispatcher = DispatcherClient(RestClient.builder().build(), "")

        assertFalse(sinDispatcher.habilitado())
        assertEquals(emptyList<Suscriptor>(), sinDispatcher.suscriptoresA(listOf("t1")))
        assertEquals(0, sinDispatcher.borrarSuscripcionesDe(listOf("t1")))
    }

    @Test
    fun `con dispatcher configurado se declara habilitado`() {
        assertTrue(client.habilitado())
    }

    @Test
    fun `una baja con cuerpo vacio se lee como cero`() {
        server.expect(requestTo(consulta("t1"))).andRespond(withSuccess())
        assertEquals(0, client.borrarSuscripcionesDe(listOf("t1")))
    }
}
