package com.citypass.gateway.service

import org.apache.avro.Schema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.web.client.RestClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Los índices se leen y se escriben desde varios hilos a la vez.
 *
 * El servicio es un singleton de Spring: los hilos de Tomcat lo usan en paralelo, y el
 * arranque recorre `schemas` mientras esos hilos pueden estar insertando. Con
 * `mutableMapOf` —que es un `LinkedHashMap`— eso es una carrera: en el mejor caso tira
 * `ConcurrentModificationException`, y en el peor deja los índices inconsistentes sin que
 * nada avise.
 *
 * Pasó de verdad: el gateway murió al arrancar con
 * `ConcurrentModificationException` en `registerSchemas` porque una petición de creación
 * llegó mientras el arranque iteraba. Tomcat ya acepta tráfico cuando se dispara
 * `ApplicationReadyEvent`, así que la ventana no es teórica.
 */
class SchemaRegistryServiceConcurrenciaTest {

    @TempDir
    lateinit var schemasDir: File

    private val kafkaAdmin: KafkaAdmin = mock()
    private val kafkaTopicAdmin: KafkaTopicAdmin = mock()
    private lateinit var service: SchemaRegistryService

    private fun schemaDe(fqn: String): Schema {
        val punto = fqn.lastIndexOf('.')
        return Schema.Parser().parse("""
        {
          "type": "record", "name": "${fqn.substring(punto + 1)}",
          "namespace": "${fqn.substring(0, punto)}",
          "fields": [{"name": "id", "type": "string"}]
        }
        """.trimIndent())
    }

    @BeforeEach
    fun setUp() {
        service = SchemaRegistryService(
            restClient = RestClient.builder().build(),
            kafkaAdmin = kafkaAdmin,
            kafkaTopicAdmin = kafkaTopicAdmin,
            schemasDir = schemasDir.absolutePath,
            schemaRegistryUrl = "http://localhost:8081",
            topicPartitions = 1,
            topicReplicationFactor = 1,
            maxPorNamespace = 1000,
            maxTotal = 5000,
            maxVersiones = 5
        )
    }

    /**
     * Reproduce el crash: iterar los índices mientras otro hilo inserta.
     *
     * `getAvailableEventTypes()` recorre `vigentes`, que es lo mismo que hace
     * `registerSchemas()` con `schemas` al arrancar. Con `LinkedHashMap` esto explota; con
     * un mapa concurrente la iteración es débilmente consistente y nunca lanza.
     */
    @Test
    @Timeout(30)
    fun `recorrer los indices mientras otro hilo inserta no explota`() {
        // Algo cargado de antes, para que haya qué recorrer desde el primer momento.
        repeat(50) { service.indexar("com.citypass.movilidad.Previo$it", schemaDe("com.citypass.movilidad.Previo$it")) }

        val error = AtomicReference<Throwable>()
        val arrancar = CountDownLatch(1)
        val listos = CountDownLatch(2)

        val escritor = Thread {
            arrancar.await()
            try {
                repeat(2_000) {
                    val fqn = "com.citypass.movilidad.Nuevo$it"
                    service.indexar(fqn, schemaDe(fqn))
                }
            } catch (t: Throwable) {
                error.compareAndSet(null, t)
            } finally {
                listos.countDown()
            }
        }

        val lector = Thread {
            arrancar.await()
            try {
                repeat(2_000) {
                    // Recorre el índice entero, igual que el arranque.
                    service.getAvailableEventTypes().forEach { _ -> }
                }
            } catch (t: Throwable) {
                error.compareAndSet(null, t)
            } finally {
                listos.countDown()
            }
        }

        escritor.start(); lector.start(); arrancar.countDown()
        listos.await(25, TimeUnit.SECONDS)

        error.get()?.let { throw AssertionError("los índices no toleran acceso concurrente: $it", it) }
    }

    /**
     * Que no lance no alcanza: dos escritores tienen que dejar **todo** lo que escribieron.
     *
     * Un `LinkedHashMap` puede perder entradas en un resize concurrente sin lanzar nada, y
     * ese es el fallo callado que importa: dos equipos crean un event type a la vez y uno
     * de los dos desaparece del índice aunque su `.avsc` esté en disco.
     *
     * **Honestidad sobre qué prueba y qué no:** al reintroducir el bug a propósito, este
     * test siguió pasando y el de arriba falló. O sea que no demuestra el defecto — la
     * pérdida de entradas es posible pero no determinística. Queda como guardia de la
     * invariante, no como reproducción. El que distingue roto de arreglado es el otro.
     */
    @Test
    @Timeout(30)
    fun `dos escritores concurrentes no pierden entradas`() {
        val arrancar = CountDownLatch(1)
        val listos = CountDownLatch(2)
        val porHilo = 1_000

        listOf("movilidad", "reclamos").forEach { ns ->
            Thread {
                arrancar.await()
                repeat(porHilo) {
                    val fqn = "com.citypass.$ns.Tipo$it"
                    service.indexar(fqn, schemaDe(fqn))
                }
                listos.countDown()
            }.start()
        }

        arrancar.countDown()
        listos.await(25, TimeUnit.SECONDS)

        assertEquals(porHilo * 2, service.getAvailableEventTypes().size,
            "se perdieron entradas al indexar desde dos hilos")
    }
}
