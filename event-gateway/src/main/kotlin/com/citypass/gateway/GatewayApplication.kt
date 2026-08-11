package com.citypass.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Punto de entrada de la aplicación Event Gateway.
 *
 * Arranca el contexto de Spring Boot con auto-configuración de Kafka,
 * seguridad JWT y Swagger/OpenAPI.
 */
@SpringBootApplication
class GatewayApplication

fun main(args: Array<String>) {
    runApplication<GatewayApplication>(*args)
}
