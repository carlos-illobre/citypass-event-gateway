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
 * Define un bean [RestClient] con HTTP/1.1 que es inyectado por
 * [com.citypass.gateway.service.SchemaRegistryService] para comunicarse con el Schema Registry
 * y por [com.citypass.gateway.service.WebhookDeliveryService] para entregar eventos via webhook.
 */
@Configuration
class RestClientConfig {

    /**
     * Crea un [RestClient] configurado con HTTP/1.1.
     *
     * Se usa HTTP/1.1 porque el Schema Registry de Confluent no soporta HTTP/2.
     *
     * @return Cliente HTTP reutilizable y thread-safe.
     */
    @Bean
    fun restClient(): RestClient = RestClient.builder()
        .requestFactory(JdkClientHttpRequestFactory(
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        ))
        .build()

    /**
     * Cliente para la entrega de webhooks, con timeouts.
     *
     * Va aparte del [restClient] general porque los destinos son distintos: el Schema
     * Registry es un servicio nuestro dentro de la misma red, y las callbackUrl son
     * servidores de otros equipos sobre los que no tenemos ningún control.
     *
     * Los timeouts son imprescindibles desde que la entrega es sincrónica respecto del
     * consumer: sin ellos, un suscriptor que acepta la conexión y no contesta nunca deja
     * el consumer de ese tópico bloqueado para siempre, y los demás suscriptores del mismo
     * tópico dejan de recibir sin que nada falle ni aparezca en la DLQ.
     *
     * @param connectTimeoutMs Tope para establecer la conexión.
     * @param readTimeoutMs Tope de espera por la respuesta una vez conectado.
     */
    @Bean
    fun webhookRestClient(
        @Value("\${gateway.webhook-connect-timeout-ms}") connectTimeoutMs: Long,
        @Value("\${gateway.webhook-read-timeout-ms}") readTimeoutMs: Long
    ): RestClient = RestClient.builder()
        .requestFactory(JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build()
        ).apply { setReadTimeout(Duration.ofMillis(readTimeoutMs)) })
        .build()
}
