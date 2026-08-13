package com.citypass.gateway.web

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity

/**
 * Construye una respuesta de error segun RFC 9457 (`application/problem+json`).
 *
 * Reemplaza al `{"status": "error", "message": ...}` ad-hoc que usaba cada controller:
 * el formato es estandar, los clientes lo parsean sin conocer nuestra convencion, y
 * Spring elige el content-type correcto al ver un [ProblemDetail] como body.
 *
 * El miembro `type` queda en `about:blank`, que es el default valido del RFC mientras
 * no haya una pagina de documentacion por tipo de error a la cual apuntar.
 *
 * @param status Codigo HTTP; se refleja tambien en el miembro `status` del body.
 * @param title Resumen corto y estable del tipo de problema.
 * @param detail Explicacion puntual de *esta* ocurrencia.
 * @param properties Miembros de extension opcionales (ej: valores validos disponibles).
 */
fun problem(
    status: HttpStatus,
    title: String,
    detail: String,
    properties: Map<String, Any> = emptyMap()
): ResponseEntity<Any> {
    val body = ProblemDetail.forStatusAndDetail(status, detail)
    body.title = title
    properties.forEach { (key, value) -> body.setProperty(key, value) }
    return ResponseEntity.status(status).body(body)
}
