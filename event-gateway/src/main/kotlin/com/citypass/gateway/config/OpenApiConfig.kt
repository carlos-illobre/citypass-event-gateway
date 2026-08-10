package com.citypass.gateway.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("CityPass+ EDA - Event Gateway")
                .version("1.0.0")
                .description(
                    """
                    **Grupo 1 - Event Driven Architecture**

                    Proxy REST que actúa como puerta de entrada al bus de eventos de CityPass+.
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

                    Los campos `eventId`, `timestamp` y `source` son inyectados automáticamente por el proxy.
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
