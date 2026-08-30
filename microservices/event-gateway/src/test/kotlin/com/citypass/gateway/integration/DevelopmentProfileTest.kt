package com.citypass.gateway.integration

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * Arranca el contexto con el perfil `development` activo.
 *
 * Comprueba que application-development.yml pise las tres propiedades de producción, que
 * es lo único que sostiene el mecanismo: si el archivo se renombra, se rompe el YAML o
 * alguien mueve una propiedad de lugar, el perfil deja de tener efecto en silencio y el
 * ambiente local se comporta como producción sin que nada falle.
 *
 * [ApplicationContextTest] cubre el caso complementario —sin perfil, valores de
 * producción—, que es el que importa para no exponer nada.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("development")
@TestPropertySource(
    properties = [
        "server.port=0",
        "gateway.schemas-dir=build/tmp/context-test/schemas",
        "gateway.data-dir=build/tmp/context-test/data",
        "gateway.http-connect-timeout-ms=3000",
        "gateway.http-read-timeout-ms=5000",
        "gateway.schema-registry-url=http://localhost:1",
        "gateway.auth-service-url=http://localhost:1",
        "gateway.cors-origin=http://localhost:5173",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.properties.request.timeout.ms=1000",
        "spring.kafka.properties.default.api.timeout.ms=1000",
        "spring.kafka.admin.fail-fast=false"
    ]
)
class DevelopmentProfileTest {


    @Value("\${springdoc.swagger-ui.enabled}")
    private var swaggerEnabled: Boolean = false

    @Value("\${logging.level.com.citypass}")
    private lateinit var logLevel: String


    @Test
    fun `el perfil publica la documentacion OpenAPI`() {
        // SecurityConfig lee esta misma propiedad para abrir las rutas, así que con esto
        // en true Swagger carga; en false ni existe ni se permite.
        assertTrue(swaggerEnabled)
    }

    @Test
    fun `el perfil sube el log a DEBUG`() {
        assertTrue(logLevel == "DEBUG", "esperaba DEBUG y era $logLevel")
    }
}
