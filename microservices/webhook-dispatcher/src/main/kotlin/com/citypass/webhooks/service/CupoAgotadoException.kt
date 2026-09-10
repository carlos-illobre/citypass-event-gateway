package com.citypass.webhooks.service

/**
 * Se superó el techo de webhooks de un event type.
 *
 * Vive acá y no en una librería compartida a propósito: el gateway tiene su propio cupo
 * —el de event types— y compartir la excepción ataría dos límites que no tienen nada que
 * ver entre sí más que el nombre.
 */
class CupoAgotadoException(mensaje: String) : RuntimeException(mensaje)
