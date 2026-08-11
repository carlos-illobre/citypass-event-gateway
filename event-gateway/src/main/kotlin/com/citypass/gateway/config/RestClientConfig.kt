package com.citypass.gateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

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
}
