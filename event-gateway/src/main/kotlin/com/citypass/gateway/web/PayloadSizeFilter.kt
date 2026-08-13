package com.citypass.gateway.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Rechaza cuerpos de petición más grandes que el máximo configurado.
 *
 * Un evento es un hecho de negocio, no un archivo: los payloads legítimos son de unos
 * pocos kilobytes. Sin tope, un grupo puede publicar un evento de cientos de megas —por
 * error, casi siempre— y ese payload se guarda en el tópico, se replica y se le entrega a
 * cada suscriptor.
 *
 * nginx ya corta en 1 MB en producción, pero eso sólo cubre el tráfico que pasa por el
 * proxy. Esta comprobación viaja con la aplicación, así que también aplica en desarrollo y
 * si alguna vez se llega al gateway por otro camino.
 *
 * Se mira `Content-Length` en vez de contar los bytes leídos porque permite cortar antes
 * de recibir el cuerpo. Un cliente que no lo declare —`Transfer-Encoding: chunked`— pasa
 * esta comprobación; ahí el tope efectivo es el de nginx.
 *
 * @param maxBytes Tamaño máximo aceptado para el cuerpo de una petición.
 */
@Component
class PayloadSizeFilter(
    @Value("\${gateway.max-payload-bytes}") private val maxBytes: Long
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        if (request.contentLengthLong <= maxBytes) {
            filterChain.doFilter(request, response)
            return
        }

        response.status = HttpStatus.PAYLOAD_TOO_LARGE.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(
            """{"type":"about:blank","title":"Payload demasiado grande","status":413,""" +
                """"detail":"El cuerpo de la petición supera el máximo de $maxBytes bytes."}"""
        )
    }
}
