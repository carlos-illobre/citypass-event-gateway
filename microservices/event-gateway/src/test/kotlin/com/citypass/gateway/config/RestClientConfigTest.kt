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
        assertNotNull(config.restClient(connectTimeoutMs = 3_000, readTimeoutMs = 5_000))
    }

    // Sin el read timeout la petición no vuelve nunca, así que el test colgaría en vez de
    // fallar. Con @Timeout la regresión se reporta como fallo y no como un build trabado.
    @Test
    @Timeout(20)
    fun `corta cuando el destino acepta la conexion y no responde`() {
        // Comprobar que el bean no es null no prueba nada: pasaría igual si alguien borra
        // los timeouts. Esto sí, porque mide el comportamiento.
        //
        // El destino que importa acá es el dispatcher: listar event types lo consulta una
        // vez por tipo, así que un dispatcher colgado sin este corte se lleva puesto el
        // pool de hilos de Tomcat y con él al gateway entero.
        val servidorMudo = ServerSocket(0)
        // Acepta la conexión y no contesta nunca: la conexión se establece, la respuesta
        // no llega. Sólo el read timeout puede cortar esto.
        val aceptador = Thread { runCatching { while (true) servidorMudo.accept() } }
            .apply { isDaemon = true; start() }

        try {
            val client = config.restClient(connectTimeoutMs = 2_000, readTimeoutMs = 500)
            val empezo = System.currentTimeMillis()

            assertThrows(Exception::class.java) {
                client.get()
                    .uri("http://localhost:${servidorMudo.localPort}/internal/suscripciones")
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
