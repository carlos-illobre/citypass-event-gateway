package com.citypass.gateway.service

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

class CallbackUrlValidatorTest {

    /**
     * Validador que resuelve todo host al literal indicado.
     *
     * Las direcciones son literales IP, así que `getByName` las parsea sin consultar
     * al DNS: los tests no dependen de la red.
     */
    private fun validadorQueResuelveA(vararg ips: String, allowPrivate: Boolean = false) =
        object : CallbackUrlValidator(allowPrivate) {
            override fun resolve(host: String): Array<InetAddress> =
                ips.map { InetAddress.getByName(it) }.toTypedArray()
        }

    private fun validadorQueNoResuelve() = object : CallbackUrlValidator(false) {
        override fun resolve(host: String): Array<InetAddress> =
            throw java.net.UnknownHostException(host)
    }

    // ── destinos aceptados ────────────────────────────────────────────────────

    @Test
    fun `una URL publica se acepta`() {
        assertNull(validadorQueResuelveA("93.184.216.34").reject("https://mi-servicio.example.com/hook"))
    }

    @Test
    fun `http tambien se acepta, no solo https`() {
        // Forzar https dejaría afuera a los grupos que no puedan poner un certificado;
        // el riesgo que cierra este validador es el destino, no el cifrado.
        assertNull(validadorQueResuelveA("93.184.216.34").reject("http://mi-servicio.example.com/hook"))
    }

    @Test
    fun `una IPv6 publica se acepta`() {
        assertNull(validadorQueResuelveA("2606:2800:220:1:248:1893:25c8:1946").reject("https://v6.example.com/hook"))
    }

    @Test
    fun `el validador real resuelve el host sin necesitar DNS para un literal IP`() {
        // Ejercita la implementación de producción de resolve(), que es la que usa Spring.
        assertNotNull(CallbackUrlValidator(false).reject("http://127.0.0.1:8080/hook"))
    }

    // ── rangos internos rechazados ────────────────────────────────────────────

    @Test
    fun `loopback se rechaza`() {
        val motivo = validadorQueResuelveA("127.0.0.1").reject("http://localhost:8080/hook")
        assertTrue(motivo!!.contains("127.0.0.1"))
        assertTrue(motivo.contains("interna"))
    }

    @Test
    fun `el endpoint de metadata del cloud se rechaza`() {
        // 169.254.169.254 devuelve credenciales de la instancia en AWS, GCP y Azure.
        assertNotNull(validadorQueResuelveA("169.254.169.254").reject("http://metadata.attacker.com/hook"))
    }

    @Test
    fun `una IP privada se rechaza`() {
        assertNotNull(validadorQueResuelveA("10.0.3.17").reject("http://interno.example.com/hook"))
        assertNotNull(validadorQueResuelveA("172.17.0.4").reject("http://interno.example.com/hook"))
        assertNotNull(validadorQueResuelveA("192.168.1.10").reject("http://interno.example.com/hook"))
    }

    @Test
    fun `la direccion comodin se rechaza`() {
        assertNotNull(validadorQueResuelveA("0.0.0.0").reject("http://cualquiera.example.com/hook"))
    }

    @Test
    fun `multicast se rechaza`() {
        assertNotNull(validadorQueResuelveA("224.0.0.1").reject("http://cualquiera.example.com/hook"))
    }

    @Test
    fun `unique local IPv6 se rechaza`() {
        // fc00::/7 es el equivalente IPv6 de 10-8 y no lo cubre isSiteLocalAddress.
        assertNotNull(validadorQueResuelveA("fd00::1").reject("http://v6interno.example.com/hook"))
        assertNotNull(validadorQueResuelveA("fc00::1").reject("http://v6interno.example.com/hook"))
    }

    @Test
    fun `loopback IPv6 se rechaza`() {
        assertNotNull(validadorQueResuelveA("::1").reject("http://v6local.example.com/hook"))
    }

    @Test
    fun `el rango de NAT de operador se rechaza`() {
        assertNotNull(validadorQueResuelveA("100.64.0.1").reject("http://cgnat.example.com/hook"))
        assertNotNull(validadorQueResuelveA("100.127.255.254").reject("http://cgnat.example.com/hook"))
    }

    @Test
    fun `100 en el primer octeto fuera del rango CGNAT se acepta`() {
        // 100.63 y 100.128 son públicas: el rango es 100.64.0.0/10, no todo 100-8.
        assertNull(validadorQueResuelveA("100.63.0.1").reject("http://publica.example.com/hook"))
        assertNull(validadorQueResuelveA("100.128.0.1").reject("http://publica.example.com/hook"))
    }

    @Test
    fun `alcanza con que una sola de las direcciones sea interna`() {
        // Un atacante puede publicar dos registros A para el mismo nombre y esperar a que
        // el cliente HTTP elija el interno.
        assertNotNull(
            validadorQueResuelveA("93.184.216.34", "169.254.169.254").reject("http://mixto.example.com/hook")
        )
    }

    // ── URLs mal formadas ─────────────────────────────────────────────────────

    @Test
    fun `una URL que no parsea se rechaza`() {
        assertNotNull(validadorQueResuelveA("93.184.216.34").reject("http://[no es una url"))
    }

    @Test
    fun `un esquema que no es http ni https se rechaza`() {
        // file:// leería archivos del contenedor del gateway.
        val motivo = validadorQueResuelveA("93.184.216.34").reject("file:///etc/passwd")
        assertTrue(motivo!!.contains("http o https"))
    }

    @Test
    fun `una URL sin esquema se rechaza`() {
        assertNotNull(validadorQueResuelveA("93.184.216.34").reject("mi-servicio.example.com/hook"))
    }

    @Test
    fun `HTTPS en mayusculas se acepta`() {
        assertNull(validadorQueResuelveA("93.184.216.34").reject("HTTPS://mi-servicio.example.com/hook"))
    }

    @Test
    fun `una URL sin host se rechaza`() {
        assertNotNull(validadorQueResuelveA("93.184.216.34").reject("http:///solo-path"))
    }

    @Test
    fun `un host que no se puede resolver se rechaza`() {
        val motivo = validadorQueNoResuelve().reject("https://no-existe.example.com/hook")
        assertTrue(motivo!!.contains("resolver"))
    }

    // ── escape de desarrollo ──────────────────────────────────────────────────

    @Test
    fun `con allow-private-callbacks se acepta un destino interno`() {
        assertNull(
            validadorQueResuelveA("127.0.0.1", allowPrivate = true).reject("http://localhost:8080/hook")
        )
    }

    @Test
    fun `allow-private-callbacks no habilita esquemas que no sean http`() {
        // El escape de desarrollo relaja el destino, no la forma de la URL.
        assertNotNull(validadorQueResuelveA("127.0.0.1", allowPrivate = true).reject("file:///etc/passwd"))
    }
}
