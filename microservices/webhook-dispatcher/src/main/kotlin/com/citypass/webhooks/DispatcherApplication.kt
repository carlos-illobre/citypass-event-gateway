package com.citypass.webhooks

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Repartidor de eventos por webhook.
 *
 * Consume del bus y entrega por HTTP a quien se haya suscripto. Vive aparte del gateway
 * porque hace un trabajo de otra naturaleza —asincrónico, con estado propio y peticiones
 * salientes a terceros— y porque así soportar webhooks es levantar o no un contenedor.
 *
 * Ver ADR-020.
 */
@SpringBootApplication
class DispatcherApplication

fun main(args: Array<String>) {
    runApplication<DispatcherApplication>(*args)
}
