package com.citypass.gateway.service

import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service

@Service
class TopicAuthorizationService {

    fun isAllowed(jwt: Jwt?, topic: String): Boolean {
        if (jwt == null) return false

        @Suppress("UNCHECKED_CAST")
        val allowedTopics = jwt.claims["allowedTopics"] as? List<String> ?: return false

        return allowedTopics.any { pattern -> matches(pattern, topic) }
    }

    // Soporta:
    //   "*"              → cualquier tópico
    //   "movilidad.*"    → cualquier tópico que empiece con "movilidad."
    //   "reclamos.creado" → solo ese tópico exacto
    private fun matches(pattern: String, topic: String): Boolean = when {
        pattern == "*"          -> true
        pattern.endsWith(".*")  -> topic.startsWith(pattern.dropLast(1))
        else                    -> pattern == topic
    }
}
