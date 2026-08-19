package com.citypass.gateway.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Verifica que una URL de callback apunte a un destino público.
 *
 * El gateway hace un POST a la `callbackUrl` de cada suscripción. Sin esta
 * verificación, cualquier grupo autenticado puede hacer que el gateway golpee
 * direcciones que sólo son alcanzables desde adentro de la red: el endpoint de
 * metadata del proveedor cloud (169.254.169.254), el Schema Registry, el broker
 * Kafka, o el propio gateway. Es un SSRF con el gateway de proxy.
 *
 * La resolución se hace **en cada intento de entrega**, no sólo al registrar la
 * suscripción. Validar sólo al registrar no sirve contra DNS rebinding: el dueño
 * de un dominio puede devolver una IP pública cuando se registra la suscripción y
 * una privada cuando el gateway entrega.
 *
 * Limitación conocida: entre esta resolución y la que hace el cliente HTTP al
 * conectar hay una ventana en la que un registro DNS con TTL 0 podría cambiar.
 * Cerrarla del todo exige un cliente HTTP con resolver propio (Apache HttpClient
 * expone `DnsResolver`; el del JDK, que es el que usamos, no). Lo que queda
 * abierto es una condición de carrera con un atacante deliberado, no el error de
 * configuración que este servicio previene.
 *
 * @param allowPrivate Acepta destinos internos. Sólo para desarrollo local, donde los
 *   consumidores corren como contenedores y tienen direcciones privadas.
 */
@Service
open class CallbackUrlValidator(
    @Value("\${gateway.allow-private-callbacks:false}") private val allowPrivate: Boolean
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Avisa al arrancar si la verificación está desactivada.
     *
     * Sin este log, un `ALLOW_PRIVATE_CALLBACKS=true` copiado a producción junto con
     * el resto del compose pasaría completamente inadvertido.
     */
    init {
        if (allowPrivate) {
            logger.warn(
                "gateway.allow-private-callbacks=true: se aceptan webhooks hacia direcciones " +
                    "internas. Es sólo para desarrollo local; en producción habilita SSRF."
            )
        }
    }

    /**
     * Motivo por el que la URL no puede usarse como callback, o `null` si es válida.
     *
     * Devolver el motivo en vez de un booleano permite que el 400 del registro le
     * diga al grupo qué está mal: el error más probable no es un ataque sino un
     * `localhost` copiado de su ambiente de desarrollo.
     *
     * @param callbackUrl URL a verificar.
     * @return Descripción del problema, o `null` si el destino es aceptable.
     */
    fun reject(callbackUrl: String): String? {
        val uri = runCatching { URI(callbackUrl) }.getOrNull()
            ?: return "'$callbackUrl' no es una URL válida."

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "El esquema de la callbackUrl debe ser http o https."
        }

        // URI.getHost() devuelve null si el host no es válido según RFC 2396 (por ejemplo
        // si tiene guiones bajos). Un host que el parser no reconoce tampoco se puede
        // resolver para verificarlo, así que no se acepta.
        val host = uri.host
            ?: return "'$callbackUrl' no tiene un host válido."

        if (allowPrivate) return null

        val addresses = runCatching { resolve(host) }.getOrNull()
            ?: return "No se pudo resolver el host '$host'. La callbackUrl tiene que ser " +
                "alcanzable desde donde corre el gateway."

        val interna = addresses.firstOrNull { isInternal(it) }
            ?: return null

        return "'$host' resuelve a ${interna.hostAddress}, que es una dirección de red " +
            "interna. La callbackUrl tiene que ser una URL pública: el gateway no corre " +
            "en la misma máquina que tu servicio."
    }

    /**
     * Resuelve un host a todas sus direcciones.
     *
     * Es un método aparte y `open` para que los tests puedan sustituirlo: depender del
     * DNS real los haría lentos y dependientes de la red.
     *
     * @param host Host de la callbackUrl.
     */
    internal open fun resolve(host: String): Array<InetAddress> = InetAddress.getAllByName(host)

    /**
     * Indica si una dirección pertenece a un rango no ruteable en Internet.
     *
     * @param address Dirección resuelta del host de la callback.
     */
    private fun isInternal(address: InetAddress): Boolean =
        address.isLoopbackAddress ||      // 127.0.0.0/8, ::1 — el propio gateway
            address.isLinkLocalAddress || // 169.254.0.0/16 — metadata de AWS/GCP/Azure
            address.isSiteLocalAddress || // 10/8, 172.16/12, 192.168/16 — la red del cluster
            address.isAnyLocalAddress ||  // 0.0.0.0, ::
            address.isMulticastAddress ||
            isUniqueLocalV6(address) ||
            isCarrierGrade(address)

    /** fc00::/7: el equivalente IPv6 de las redes privadas, que `isSiteLocalAddress` no cubre. */
    private fun isUniqueLocalV6(address: InetAddress): Boolean =
        address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC

    /** 100.64.0.0/10: NAT de operador, que varios proveedores cloud usan entre nodos. */
    private fun isCarrierGrade(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val bytes = address.address
        return (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xFF) in 64..127
    }
}
