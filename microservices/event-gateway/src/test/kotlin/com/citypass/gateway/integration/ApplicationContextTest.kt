package com.citypass.gateway.integration

import com.citypass.gateway.controller.DlqController
import com.citypass.gateway.controller.EventController
import com.citypass.gateway.controller.SubscriptionController
import com.citypass.gateway.service.CallbackUrlValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.TestPropertySource

/**
 * Arranca el contexto completo de Spring.
 *
 * El resto de los tests construye los controllers y servicios a mano, así que un error de
 * cableado —una anotación que falta, un `@Value` que ningún archivo define, un bean sin
 * candidato— no lo detecta ninguno: compilan y pasan igual, y el fallo recién aparece al
 * arrancar el contenedor.
 *
 * No necesita broker ni Schema Registry: los beans de Kafka no se conectan al crearse, y
 * `schemas-dir` apunta a un directorio vacío para que el registro de esquemas del arranque
 * no tenga nada que hacer. Los timeouts de Kafka se bajan a un segundo porque `KafkaAdmin`
 * sí intenta conectarse al inicializar el `NewTopic` de la DLQ; falla y sigue, pero con los
 * valores por defecto tardaría un minuto en darse por vencido.
 */
@Tag("integration")
@SpringBootTest
@TestPropertySource(
    properties = [
        // Esta lista es, de hecho, la configuración mínima para arrancar: cada propiedad
        // está acá porque application.yml la exige sin default.
        "server.port=0",
        "gateway.schemas-dir=build/tmp/context-test/schemas",
        "gateway.data-dir=build/tmp/context-test/data",
        "gateway.schema-registry-url=http://localhost:1",
        "gateway.auth-service-url=http://localhost:1",
        "gateway.dlq-topic=sistema.dlq",
        "gateway.cors-origin=http://localhost:5173",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.properties.request.timeout.ms=1000",
        "spring.kafka.properties.default.api.timeout.ms=1000",
        "spring.kafka.admin.fail-fast=false"
    ]
)
class ApplicationContextTest {

    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var callbackUrlValidator: CallbackUrlValidator

    @Test
    fun `el contexto arranca y expone los controllers`() {
        assertNotNull(context.getBean(EventController::class.java))
        assertNotNull(context.getBean(SubscriptionController::class.java))
        assertNotNull(context.getBean(DlqController::class.java))
    }

    @Test
    fun `el JwtDecoder se construye a partir de la configuracion`() {
        // Es el bean que define quién puede entrar: si no se construye, el gateway
        // arranca sin autenticación o directamente no arranca.
        assertNotNull(context.getBean(JwtDecoder::class.java))
    }

    @Test
    fun `allow-private-callbacks queda en false cuando nadie lo define`() {
        // El default del `@Value` es lo que protege al despliegue en la nube: ahí la
        // variable no está seteada, y si el default fuera true el SSRF quedaría abierto
        // sin que nadie hubiera tocado nada.
        // Direcciones literales: se parsean sin consultar al DNS, así que el test no
        // depende de que haya red.
        assertNotNull(callbackUrlValidator.reject("http://127.0.0.1:8080/hook"))
        assertNull(callbackUrlValidator.reject("https://93.184.216.34/hook"))
    }

    @Test
    fun `el validador que reciben el controller y el servicio de entrega es el mismo bean`() {
        // Si fueran dos instancias con configuración distinta, el 400 del registro y el
        // bloqueo de la entrega podrían no coincidir.
        assertEquals(1, context.getBeanNamesForType(CallbackUrlValidator::class.java).size)
    }
}
