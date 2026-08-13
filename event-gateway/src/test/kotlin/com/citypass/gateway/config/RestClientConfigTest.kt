package com.citypass.gateway.config

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket

class RestClientConfigTest {

    private val config = RestClientConfig()

    @Test
    fun `restClient bean is created and is not null`() {
        val restClient = config.restClient()
        assertNotNull(restClient)
    }

    @Test
    fun `webhookRestClient se construye`() {
        assertNotNull(config.webhookRestClient(connectTimeoutMs = 5_000, readTimeoutMs = 10_000))
    }

    // Sin el read timeout la petición no vuelve nunca, así que el test colgaría en vez de
    // fallar. Con @Timeout la regresión se reporta como fallo y no como un build trabado.
    @Test
    @Timeout(20)
    fun `webhookRestClient corta cuando el destino acepta la conexion y no responde`() {
        // Comprobar que el bean no es null no prueba nada: pasaría igual si alguien borra
        // los timeouts. Esto sí, porque mide el comportamiento.
        //
        // Es el escenario que dejó de ser inofensivo cuando la entrega pasó a ser
        // sincrónica: antes un suscriptor colgado se llevaba un hilo, ahora bloquea el
        // consumer del tópico y los demás suscriptores dejan de recibir sin que nada falle.
        val servidorMudo = ServerSocket(0)
        // Acepta la conexión y no contesta nunca: la conexión se establece, la respuesta
        // no llega. Sólo el read timeout puede cortar esto.
        val aceptador = Thread { runCatching { while (true) servidorMudo.accept() } }
            .apply { isDaemon = true; start() }

        try {
            val client = config.webhookRestClient(connectTimeoutMs = 2_000, readTimeoutMs = 500)
            val empezo = System.currentTimeMillis()

            assertThrows(Exception::class.java) {
                client.post()
                    .uri("http://localhost:${servidorMudo.localPort}/hook")
                    .body(mapOf("x" to 1))
                    .retrieve()
                    .toBodilessEntity()
            }

            val tardo = System.currentTimeMillis() - empezo
            assertTrue(tardo < 10_000, "cortó a los ${tardo}ms; sin read timeout no cortaría nunca")
        } finally {
            servidorMudo.close()
            aceptador.interrupt()
        }
    }
}
