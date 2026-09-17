package com.citypass.gateway.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/**
 * Configuración del cliente HTTP compartido.
 *
 * Define un bean [RestClient] con HTTP/1.1 que usan
 * [com.citypass.gateway.service.SchemaRegistryService] para hablar con el Schema Registry y
 * [com.citypass.gateway.service.DispatcherClient] para consultar las suscripciones.
 *
 * Desde el ADR-020 el gateway no entrega webhooks, así que ya no hay un cliente aparte para
 * destinos ajenos: los dos destinos que le quedan son servicios nuestros de la misma red.
 */
@Configuration
class RestClientConfig {

    /**
     * Cliente HTTP del gateway, con timeouts.
     *
     * Se usa HTTP/1.1 porque el Schema Registry de Confluent no soporta HTTP/2.
     *
     * Los timeouts no son decorativos: listar event types consulta al dispatcher una vez por
     * tipo, así que es un camino frecuente y no una operación rara. Un dispatcher que acepta
     * la conexión y no contesta dejaría hilos de Tomcat tomados hasta agotar el pool, y el
     * gateway entero dejaría de responder por culpa de un servicio que es opcional.
     *
     * @param connectTimeoutMs Tope para establecer la conexión.
     * @param readTimeoutMs Tope de espera por la respuesta una vez conectado.
     * @return Cliente HTTP reutilizable y thread-safe.
     */
    @Bean
    fun restClient(
        @Value("\${gateway.http-connect-timeout-ms}") connectTimeoutMs: Long,
        @Value("\${gateway.http-read-timeout-ms}") readTimeoutMs: Long
    ): RestClient = RestClient.builder()
        .requestFactory(JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build()
        ).apply { setReadTimeout(Duration.ofMillis(readTimeoutMs)) })
        .build()
}
