package com.citypass.gateway.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuración de la documentación Swagger/OpenAPI.
 *
 * Define el título, descripción y esquema de autenticación Bearer JWT
 * que se muestran en la UI interactiva de Swagger (/swagger-ui/index.html).
 */
@Configuration
class OpenApiConfig {

    /**
     * Crea el documento OpenAPI con la información del servicio y el esquema de seguridad.
     *
     * @return Documento OpenAPI configurado para el Event Gateway.
     */
    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("CityPass+ EDA - Event Gateway")
                .version("1.0.0")
                .description(
                    """
                    **Grupo 1 - Event Driven Architecture**

                    Puerta de entrada HTTP al bus de eventos de CityPass+.
                    Recibe mensajes JSON y los convierte a formato Avro antes de publicarlos en Kafka.

                    ## Autenticación

                    Los endpoints de escritura requieren un token JWT en el header:
                    ```
                    Authorization: Bearer <token>
                    ```

                    Obtené el token con `POST /auth/login` en el servicio de autenticación.

                    ## ¿Cómo publicar un evento?

                    Hacer un `POST /api/v1/events` con el siguiente body:
                    ```json
                    {
                      "eventType": "<nombre del tópico>",
                      "source": "<nombre de tu servicio>",
                      "data": { ... campos del evento ... }
                    }
                    ```

                    Los campos `eventId`, `timestamp` y `source` son inyectados automáticamente por el gateway.
                    """.trimIndent()
                )
        )
        .components(
            Components().addSecuritySchemes(
                "bearerAuth",
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Token JWT obtenido del servicio de autenticación (POST /auth/login)")
            )
        )
}
