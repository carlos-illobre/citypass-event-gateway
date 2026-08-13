package com.citypass.gateway.service

import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service

/**
 * Servicio de autorización de publicación por tópico.
 *
 * Valida si un usuario tiene permiso para publicar en un tópico determinado
 * comparando el claim `namespace` del JWT con el prefijo del tópico destino.
 *
 * Un tópico sigue el formato `namespace.NombreEvento` (FQN Avro), por lo que
 * basta verificar que el tópico empiece con el namespace del usuario seguido de un punto.
 *
 * No hay ningún namespace privilegiado: un comodín sería una llave maestra sobre todos
 * los tópicos, y bastaría con que se filtrara esa credencial para comprometer el bus
 * entero. Cada grupo puede publicar exactamente en lo suyo.
 */
@Service
class TopicAuthorizationService {

    /**
     * Verifica si el usuario autenticado tiene permiso para publicar en el tópico dado.
     *
     * Lee el claim `namespace` del JWT y verifica que el tópico empiece con ese namespace.
     *
     * @param jwt Token JWT del usuario autenticado (null si no hay autenticación).
     * @param topic Nombre completo del tópico (FQN Avro, ej: "com.citypass.movilidad.BiciDevuelta").
     * @return true si el usuario tiene permiso, false si no tiene JWT, claim namespace ausente o no coincide.
     */
    fun isAllowed(jwt: Jwt?, topic: String): Boolean {
        if (jwt == null) return false
        val namespace = jwt.claims["namespace"] as? String ?: return false
        return topic.startsWith("$namespace.")
    }
}
