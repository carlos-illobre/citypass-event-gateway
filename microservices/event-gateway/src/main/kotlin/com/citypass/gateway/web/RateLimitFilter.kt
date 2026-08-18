package com.citypass.gateway.web

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Limita cuántas peticiones por minuto puede hacer cada grupo.
 *
 * No está pensado para frenar a un atacante —para eso está la autenticación— sino para
 * que el error de un grupo no se lleve puesto al resto: un loop publicando eventos llena
 * el disco del broker y deja sin servicio a los otros siete equipos, y del lado de la
 * víctima se ve como que el bus «se puso lento».
 *
 * La cuenta es por namespace y no por usuario: el namespace identifica a la aplicación,
 * que es la que consume la cuota, y así dos instancias del mismo grupo no la duplican.
 *
 * El estado vive en memoria, o sea que el límite es por instancia. Con una sola instancia
 * —que es lo que hay hoy— es exacto; con varias, el límite efectivo se multiplica por la
 * cantidad de instancias. Moverlo a un contador compartido sólo tiene sentido junto con el
 * resto del estado compartido, no antes.
 *
 * @param limitePorMinuto Peticiones permitidas por namespace en cada ventana de un minuto.
 * @param meterRegistry Para poder ver los rechazos sin depender de que alguien lea el log.
 */
@Component
class RateLimitFilter(
    @Value("\${gateway.rate-limit-per-minute}") private val limitePorMinuto: Int,
    private val meterRegistry: MeterRegistry
) : OncePerRequestFilter() {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Contador y comienzo de la ventana vigente de cada namespace. */
    private class Ventana(val inicio: Long, val cuenta: AtomicLong = AtomicLong(0))

    private val ventanas = ConcurrentHashMap<String, Ventana>()

    /**
     * Deja pasar la petición o la corta con 429.
     *
     * Las peticiones sin token no se limitan acá: no tienen namespace al que imputarles la
     * cuota, y la cadena de seguridad ya las va a rechazar con 401. Las únicas que pasan
     * sin token son `/health` y la resolución pública de esquemas, que no escriben nada.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val namespace = namespaceDe()
        if (namespace == null || permitir(namespace)) {
            filterChain.doFilter(request, response)
            return
        }

        logger.warn("Rate limit excedido por '$namespace' ($limitePorMinuto/min)")
        meterRegistry.counter("citypass.rate_limit.rechazos", "namespace", namespace).increment()

        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        // Sin Retry-After el cliente no sabe cuánto esperar y lo más probable es que
        // reintente en seguida, que es justo lo que agrava el problema.
        response.setHeader("Retry-After", "60")
        response.writer.write(
            """{"type":"about:blank","title":"Demasiadas peticiones",""" +
                """"status":429,"detail":"El namespace '$namespace' superó las """ +
                """$limitePorMinuto peticiones por minuto. Reintentá en 60 segundos."}"""
        )
    }

    /**
     * Namespace del token de la petición, o `null` si no hay token.
     */
    private fun namespaceDe(): String? {
        // `token` y `claims` nunca son nulos en un JwtAuthenticationToken, así que no se
        // encadenan `?.`: serían ramas que ningún test puede alcanzar.
        val auth = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
            ?: return null
        return auth.token.claims["namespace"] as? String
    }

    /**
     * Consume una unidad de la cuota del namespace.
     *
     * Ventana fija: al vencer el minuto se descarta la anterior y empieza una nueva. Es
     * menos preciso que una ventana deslizante en el borde entre dos minutos, pero no
     * necesita guardar el historial de cada petición.
     *
     * @return true si la petición entra dentro de la cuota.
     */
    private fun permitir(namespace: String): Boolean {
        val ahora = System.currentTimeMillis()
        val ventana = ventanas.compute(namespace) { _, actual ->
            if (actual == null || ahora - actual.inicio >= 60_000) Ventana(ahora) else actual
        }!!
        return ventana.cuenta.incrementAndGet() <= limitePorMinuto
    }
}
