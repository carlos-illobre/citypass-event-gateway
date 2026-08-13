package com.citypass.gateway.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OpenApiConfigTest {

    @Test
    fun `openApi bean returns valid OpenAPI document`() {
        val config = OpenApiConfig()
        val openApi = config.openApi()

        assertNotNull(openApi)
        assertNotNull(openApi.info)
        assertEquals("CityPass+ EDA - Event Gateway", openApi.info.title)
        assertEquals("1.0.0", openApi.info.version)
    }

    /** Documento mínimo: una operación con las respuestas indicadas. */
    private fun documentoCon(vararg respuestas: Pair<String, ApiResponse>): OpenAPI {
        val operacion = Operation().responses(ApiResponses().apply {
            respuestas.forEach { (codigo, respuesta) -> addApiResponse(codigo, respuesta) }
        })
        return OpenAPI().paths(Paths().addPathItem("/x", PathItem().get(operacion)))
    }

    private fun respuestasDe(doc: OpenAPI) = doc.paths["/x"]!!.get.responses

    @Test
    fun `las respuestas de error se documentan como problem+json`() {
        // Sin esto Swagger mostraba un media type comodín con un {} vacío, porque los
        // controllers devuelven ResponseEntity<Any> y springdoc no puede inferir el tipo.
        val doc = documentoCon("404" to ApiResponse(), "500" to ApiResponse())

        OpenApiConfig().erroresComoProblemDetail().customise(doc)

        listOf("404", "500").forEach { codigo ->
            val contenido = respuestasDe(doc)[codigo]!!.content
            assertNotNull(contenido["application/problem+json"], "falta el contenido en $codigo")
            assertNotNull(contenido["application/problem+json"]!!.example)
        }
    }

    @Test
    fun `no toca las respuestas exitosas`() {
        // El 200 de cada endpoint tiene su propio ejemplo; describirlo como un error sería
        // peor que dejarlo vacío.
        val doc = documentoCon("200" to ApiResponse(), "202" to ApiResponse())

        OpenApiConfig().erroresComoProblemDetail().customise(doc)

        assertNull(respuestasDe(doc)["200"]!!.content)
        assertNull(respuestasDe(doc)["202"]!!.content)
    }

    @Test
    fun `al 204 le saca el comodín en vez de reemplazarlo`() {
        // Un 204 no tiene cuerpo: documentarlo como problem+json sería mentir, y dejar el
        // comodín muestra un `{}` que sugiere que devuelve algo.
        val comodin = ApiResponse().content(Content().addMediaType("*/*", MediaType()))
        val doc = documentoCon("204" to comodin)

        OpenApiConfig().erroresComoProblemDetail().customise(doc)

        assertNull(respuestasDe(doc)["204"]!!.content)
    }

    @Test
    fun `respeta el contenido que ya declara el endpoint`() {
        val propio = ApiResponse().content(Content().addMediaType("application/json", MediaType()))
        val doc = documentoCon("409" to propio)

        OpenApiConfig().erroresComoProblemDetail().customise(doc)

        assertNull(respuestasDe(doc)["409"]!!.content["application/problem+json"])
    }

    @Test
    fun `un código que no es numérico no rompe el recorrido`() {
        // OpenAPI admite `default` como clave de respuesta.
        val doc = documentoCon("default" to ApiResponse())

        OpenApiConfig().erroresComoProblemDetail().customise(doc)

        assertNull(respuestasDe(doc)["default"]!!.content)
    }

    @Test
    fun `un documento sin paths no rompe`() {
        OpenApiConfig().erroresComoProblemDetail().customise(OpenAPI())
    }

    @Test
    fun `una operación sin respuestas declaradas no rompe`() {
        val doc = OpenAPI().paths(Paths().addPathItem("/x", PathItem().get(Operation())))

        OpenApiConfig().erroresComoProblemDetail().customise(doc)

        assertNull(doc.paths["/x"]!!.get.responses)
    }

    @Test
    fun `reemplaza el marcador de posición que genera springdoc`() {
        // springdoc no deja el contenido nulo: cuando el controller devuelve un tipo que
        // no puede inspeccionar —ResponseEntity<Any>—, pone un comodín con un schema
        // `object` vacío. Ese es el caso real, y el que hacía que Swagger mostrara `{}`.
        val comodin = ApiResponse().content(
            Content().addMediaType("*/*", MediaType().schema(Schema<Any>().type("object")))
        )
        val doc = documentoCon("404" to comodin)

        OpenApiConfig().erroresComoProblemDetail().customise(doc)

        val contenido = respuestasDe(doc)["404"]!!.content
        assertNull(contenido["*/*"])
        assertNotNull(contenido["application/problem+json"]!!.example)
    }
}
