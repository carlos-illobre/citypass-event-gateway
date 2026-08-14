package com.citypass.gateway.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuración de la documentación Swagger/OpenAPI.
 *
 * Define el título, descripción y esquema de autenticación Bearer JWT
 * que se muestran en la UI interactiva de Swagger (/swagger-ui/index.html).
 */
@Configuration
class OpenApiConfig(private val buildProperties: BuildProperties) {

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
                // Sale del build, igual que `metadata.gatewayVersion` de cada evento.
                // Escrita a mano quedaba desincronizada: el documento se declaraba 1.0.0
                // mientras el gateway publicaba eventos sellados con 0.0.1-SNAPSHOT.
                .version(buildProperties.version)
                .description(
                    """
                    **Grupo 1 - Event Driven Architecture**

                    Puerta de entrada HTTP al bus de eventos de CityPass+.
                    Recibe mensajes JSON y los convierte a formato Avro antes de publicarlos en Kafka.

                    ## Autenticación

                    Salvo `/health` y la resolución pública de schemas por id, todos los
                    endpoints exigen un token JWT en el header:
                    ```
                    Authorization: Bearer <token>
                    ```

                    Se obtiene con `POST /oauth/token` en el servicio de identidad, usando
                    `grant_type=client_credentials`.

                    ## ¿Cómo publicar un evento?

                    `POST /api/v1/event-types/{fqn}/events`, con **sólo los campos de negocio**
                    en el body:
                    ```json
                    { "userId": "user-42", "biciId": "bici-101" }
                    ```

                    Lo que se publica es un envelope de dos records: `data` con lo que enviaste
                    y `metadata` que calcula el gateway a partir del token y del payload
                    —`eventId`, `source`, `payloadHash`, `schemaId`—. No hay forma de escribir
                    `metadata` desde el request, y por eso `source` es confiable.

                    Los errores siguen RFC 9457 (`application/problem+json`).
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
                    .description("Token JWT del servicio de identidad (POST /oauth/token)")
            )
        )

    /**
     * Documenta el cuerpo de los errores en todas las operaciones.
     *
     * Todos los errores del gateway son RFC 9457, así que describirlos uno por uno en cada
     * `@ApiResponse` serían dos docenas de anotaciones repetidas que además hay que
     * acordarse de poner en cada endpoint nuevo. Sin ellas, springdoc no puede inferir el
     * tipo —los controllers devuelven `ResponseEntity<Any>`— y Swagger muestra un media
     * type comodín con un `{}` vacío, que es lo que se veía.
     *
     * Sólo reemplaza el contenido de las respuestas de error que no documentan uno propio.
     * springdoc no las deja vacías: les pone un comodín `*` con un schema `object` sin
     * campos, que es lo que Swagger muestra como un `{}` inútil. Si un endpoint declara su
     * propio media type, gana el del endpoint.
     */
    @Bean
    fun erroresComoProblemDetail(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        // Un solo chequeo de nulo: `values` de un Paths nunca lo es, así que encadenar
        // otro `?.` dejaría una rama que ningún test puede alcanzar.
        val paths = openApi.paths ?: return@OpenApiCustomizer
        paths.values.forEach { path ->
            path.readOperations().forEach { operacion ->
                operacion.responses?.forEach { (codigo, respuesta) ->
                    val numero = codigo.toIntOrNull()
                    if (sinDocumentar(respuesta.content)) {
                        // Un 204 no tiene cuerpo: ahí el comodín no es falta de
                        // documentación, es ruido, y se saca en vez de reemplazarse.
                        if (numero == 204) respuesta.content = null
                        else if (numero != null && numero >= 400) respuesta.content = problemContent()
                    }
                }
            }
        }
    }

    /**
     * Indica si una respuesta no documenta su cuerpo.
     *
     * Vale tanto para `content` nulo como para el marcador de posición que genera
     * springdoc cuando el controller devuelve un tipo que no puede inspeccionar.
     */
    private fun sinDocumentar(content: Content?): Boolean =
        content == null || content.keys == setOf("*/*")

    /** Cuerpo `application/problem+json` con la forma de RFC 9457 y un ejemplo. */
    private fun problemContent(): Content = Content().addMediaType(
        "application/problem+json",
        io.swagger.v3.oas.models.media.MediaType()
            .schema(
                Schema<Any>()
                    .type("object")
                    .addProperty("type", Schema<Any>().type("string"))
                    .addProperty("title", Schema<Any>().type("string"))
                    .addProperty("status", Schema<Any>().type("integer"))
                    .addProperty("detail", Schema<Any>().type("string"))
                    .addProperty("instance", Schema<Any>().type("string"))
            )
            .example(
                mapOf(
                    "type" to "about:blank",
                    "title" to "Event type no encontrado",
                    "status" to 404,
                    "detail" to "No hay ningún event type registrado con el FQN 'com.citypass.movilidad.NoExiste'.",
                    "instance" to "/api/v1/event-types/com.citypass.movilidad.NoExiste/events"
                )
            )
    )
}
