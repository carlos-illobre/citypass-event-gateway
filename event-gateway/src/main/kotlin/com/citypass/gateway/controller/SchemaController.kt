package com.citypass.gateway.controller

import com.citypass.gateway.service.CambioDeEsquema
import com.citypass.gateway.service.CupoAgotadoException
import com.citypass.gateway.service.SchemaChangeNotifier
import com.citypass.gateway.service.SchemaRegistryService
import com.citypass.gateway.service.SubscriptionService
import com.citypass.gateway.service.TopicAuthorizationService
import com.citypass.gateway.web.problem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Controller CRUD de event types.
 *
 * Un event type debe existir antes de que se pueda publicar un evento suyo. Su
 * representación es el schema Avro completo y su identificador es el FQN
 * (`namespace.Name`), que no cambia nunca.
 *
 * Al registrar, el cliente provee el name y los fields de negocio. El gateway lo
 * envuelve en un record de dos campos —`metadata` (que calcula él) y `data` (los
 * campos del productor)— y obtiene el namespace del claim JWT del emisor.
 *
 * El namespace no viaja en la ruta a propósito: ya está contenido en el FQN, y en
 * escritura no lo elige el cliente sino el JWT. Para acotar el listado a un
 * namespace está el query param `?namespace=`.
 *
 * @param schemaRegistryService Servicio que gestiona schemas locales y su registro en Confluent Schema Registry.
 * @param subscriptionService Suscripciones webhook, para saber a quién afecta un borrado.
 * @param topicAuthorizationService Comprueba que el event type sea del namespace del emisor.
 * @param schemaChangeNotifier Publica el aviso de cambio de contrato en el bus.
 */
@RestController
@RequestMapping("/api/v1/event-types")
@Tag(name = "Event types", description = "Registro y consulta de los tipos de evento del bus")
class SchemaController(
    private val schemaRegistryService: SchemaRegistryService,
    private val subscriptionService: SubscriptionService,
    private val topicAuthorizationService: TopicAuthorizationService,
    private val schemaChangeNotifier: SchemaChangeNotifier
) {

    private val mapper = jacksonObjectMapper()

    /**
     * Devuelve el schema como objeto y no como String.
     *
     * `Schema.toString()` produce JSON, pero entregarlo como String hace que Spring
     * lo escriba con el converter de texto: `text/plain;charset=ISO-8859-1`, que
     * miente sobre el tipo y corrompe cualquier acento en los `doc` del schema.
     */
    private fun asJson(schema: org.apache.avro.Schema): Map<*, *> =
        mapper.readValue(schema.toString(), Map::class.java)

    @Operation(
        summary = "Listar event types",
        description = """Devuelve un resumen de cada event type registrado.

`topic` es dónde se publican los eventos nuevos, y `versions` lista todas las versiones
mayores que existen — las viejas siguen sirviendo su historial.

Con `?namespace=` acota el resultado a los de un equipo.""",
        responses = [ApiResponse(
                responseCode = "200", description = "Lista de event types",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Event types",
                        summary = "Un event type que nunca rompió su contrato tiene una sola versión y su tópico es el FQN",
                        value = """[
  {
    "fqn": "com.citypass.movilidad.BicicletaReservada",
    "namespace": "com.citypass.movilidad",
    "name": "BicicletaReservada",
    "topic": "com.citypass.movilidad.BicicletaReservada.v2",
    "version": 2,
    "schemaId": 12,
    "versions": [
      { "version": 1, "topic": "com.citypass.movilidad.BicicletaReservada",    "schemaId": 7  },
      { "version": 2, "topic": "com.citypass.movilidad.BicicletaReservada.v2", "schemaId": 12 }
    ]
  }
]"""
                    )]
                )]
            )]
    )
    @GetMapping
    fun list(
        @Parameter(description = "Filtra por namespace exacto, ej: com.citypass.movilidad")
        @RequestParam(required = false) namespace: String?
    ): ResponseEntity<Any> =
        ResponseEntity.ok(schemaRegistryService.listEventTypes(namespace))

    @Operation(
        summary = "Cuántos event types puedo crear",
        description = """Devuelve el cupo del namespace de quien pregunta.

Se cuentan nombres lógicos, no tópicos: las versiones mayores de un mismo event type son
el mismo contrato y no consumen cupo aparte.""",
        responses = [ApiResponse(
            responseCode = "200", description = "Cupo del namespace",
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = [ExampleObject(name = "Cupo", value = """{
  "namespace": "com.citypass.movilidad",
  "used": 12,
  "limit": 25,
  "remaining": 13
}""")]
            )]
        )],
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    // Va antes de /{fqn} en el archivo por claridad; Spring resuelve primero la ruta
    // literal, así que un event type llamado «quota» no la taparía igual.
    @GetMapping("/quota")
    fun quota(@AuthenticationPrincipal jwt: Jwt?): ResponseEntity<Any> {
        if (jwt == null) return sinToken()
        val namespace = jwt.claims["namespace"] as? String ?: return sinNamespace()
        return ResponseEntity.ok(schemaRegistryService.cupoDe(namespace))
    }

    @Operation(
        summary = "Ver el schema de un event type",
        description = """Devuelve el schema de la **versión vigente**.

Para ver el de una versión anterior se pasa su tópico completo, con el sufijo:
`/api/v1/event-types/com.citypass.movilidad.BiciDevuelta.v2`.""",
        responses = [
            ApiResponse(
                responseCode = "200", description = "Schema Avro",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Envelope del event type",
                        summary = "El record raíz tiene dos campos: data con lo del productor y metadata con lo del gateway",
                        value = """{
  "type": "record",
  "name": "BicicletaReservada",
  "namespace": "com.citypass.movilidad",
  "fields": [
    { "name": "data", "type": { "type": "record", "name": "BicicletaReservada", "namespace": "com.citypass.movilidad.data", "fields": [] } },
    { "name": "metadata", "type": "com.citypass.gateway.EventMetadata" }
  ]
}"""
                    )]
                )]
            ),
            ApiResponse(responseCode = "404", description = "Event type no encontrado")
        ]
    )
    @GetMapping("/{fqn}")
    fun get(@PathVariable fqn: String): ResponseEntity<Any> {
        val tipo = schemaRegistryService.resolver(fqn)
            ?: return problem(
                HttpStatus.NOT_FOUND,
                "Event type no encontrado",
                "No hay ningún event type registrado con el FQN '$fqn'."
            )
        return ResponseEntity.ok(asJson(tipo.schema))
    }

    @Operation(
        summary = "Registrar un nuevo event type",
        description = """Registra un tipo de evento nuevo.

El namespace se obtiene del claim JWT del emisor. Los campos enviados son los de
negocio: el gateway los coloca en el record `data` y antepone el record `metadata`
(ver GET /api/v1/event-metadata). Al vivir en records separados no hay nombres
reservados — un campo puede llamarse `source` o `eventId` sin conflicto.

El tópico Kafka resultante es el FQN: namespace.Name.

Si el event type ya existe, esto devuelve 400: para cambiarle el schema está el PUT.""",
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = Map::class),
                examples = [ExampleObject(
                    name = "Schema de ejemplo",
                    summary = "Registrar un nuevo tipo de evento",
                    value = """{
  "name": "BiciDevuelta",
  "fields": [
    {"name": "biciId",     "type": "string"},
    {"name": "userId",     "type": "string"},
    {"name": "estacionId", "type": "string"},
    {"name": "duracionMin","type": "int"}
  ]
}"""
                )]
            )]
        ),
        responses = [
            ApiResponse(
                responseCode = "201", description = "Event type registrado",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Registrado",
                        summary = "El tópico se crea en el momento del registro, no al publicar el primer evento",
                        value = """{
  "fqn": "com.citypass.movilidad.BicicletaReservada",
  "namespace": "com.citypass.movilidad",
  "name": "BicicletaReservada",
  "topic": "com.citypass.movilidad.BicicletaReservada",
  "version": 1,
  "schemaId": 7
}"""
                    )]
                )]
            ),
            ApiResponse(responseCode = "400", description = "Schema inválido o el event type ya existe"),
            ApiResponse(responseCode = "409", description = "El namespace llegó a su máximo de event types"),
            ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
            ApiResponse(responseCode = "502", description = "El Schema Registry rechazó el registro")
        ],
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @PostMapping
    fun register(
        @RequestBody request: Map<String, Any>,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<Any> {
        val name = request["name"] as? String
            ?: return problem(
                HttpStatus.BAD_REQUEST, "Falta un campo requerido",
                "El campo 'name' es obligatorio."
            )

        if (jwt == null) return sinToken()

        val namespace = jwt.claims["namespace"] as? String ?: return sinNamespace()
        val fields = camposDe(request) ?: return faltanCampos()

        return schemaRegistryService.registerNewSchema(namespace, name, fields).fold(
            onSuccess = { schemaId ->
                val fqn = "$namespace.$name"
                ResponseEntity
                    .created(java.net.URI("/api/v1/event-types/$fqn"))
                    .body(mapOf(
                        "fqn" to fqn, "namespace" to namespace, "name" to name,
                        "topic" to fqn, "version" to 1, "schemaId" to schemaId
                    ))
            },
            onFailure = { error -> errorDeRegistro(error) }
        )
    }

    @Operation(
        summary = "Cambiar el schema de un event type",
        description = """Reemplaza los campos de negocio de un event type existente.

El FQN no puede cambiar: identifica al event type y viaja en la ruta. Lo que se envía es
la lista **completa** de campos nuevos, igual que en el POST.

Qué pasa lo decide el Schema Registry, no quien llama:

- **Cambio compatible** (agregar un campo con `default`, ensanchar un `int` a `long`):
  se registra en el mismo tópico. Ningún consumidor se entera, ninguna suscripción se toca.
- **Cambio incompatible** (quitar un campo sin default, reestructurar, cambiar un tipo por
  otro que no lo admite): estrena una **versión mayor** con tópico propio, `<fqn>.v2`. La
  anterior queda intacta sirviendo su historial, para que los consumidores migren cuando
  puedan. La respuesta trae `breaking: true` y cuántas suscripciones quedaron en la vieja.
- **Schema idéntico** al vigente: no hace nada y devuelve `unchanged: true`.

En los dos primeros casos se publica un evento `com.citypass.gateway.EsquemaCambiado`,
al que cualquier equipo puede suscribirse para enterarse sin preguntar.""",
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = Map::class),
                examples = [ExampleObject(
                    name = "Reestructurar campos",
                    summary = "Mover nombre y apellido adentro de un record: cambio incompatible, estrena versión",
                    value = """{
  "fields": [
    {"name": "id", "type": "string"},
    {"name": "usuario", "type": {
      "type": "record", "name": "Usuario",
      "fields": [
        {"name": "nombre",   "type": "string"},
        {"name": "apellido", "type": "string"}
      ]
    }}
  ]
}"""
                )]
            )]
        ),
        responses = [
            ApiResponse(
                responseCode = "200", description = "Schema actualizado",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Cambio incompatible",
                        summary = "breaking indica que estrenó versión; los eventos nuevos van a topic",
                        value = """{
  "fqn": "com.citypass.movilidad.BiciDevuelta",
  "topic": "com.citypass.movilidad.BiciDevuelta.v2",
  "version": 2,
  "schemaId": 12,
  "breaking": true,
  "unchanged": false,
  "previousTopic": "com.citypass.movilidad.BiciDevuelta",
  "subscriptionsOnPreviousVersion": 3
}"""
                    )]
                )]
            ),
            ApiResponse(responseCode = "400", description = "Schema inválido o campos ausentes"),
            ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
            ApiResponse(responseCode = "403", description = "El event type es de otro equipo"),
            ApiResponse(responseCode = "404", description = "Event type no encontrado"),
            ApiResponse(responseCode = "502", description = "El Schema Registry no respondió")
        ],
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @PutMapping("/{fqn}")
    fun update(
        @PathVariable fqn: String,
        @RequestBody request: Map<String, Any>,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<Any> {
        if (jwt == null) return sinToken()
        val namespace = jwt.claims["namespace"] as? String ?: return sinNamespace()
        if (!topicAuthorizationService.isAllowed(jwt, fqn)) return ajeno(fqn, "cambiar")

        val fields = camposDe(request) ?: return faltanCampos()
        val name = fqn.removePrefix("$namespace.")

        return schemaRegistryService.updateSchema(namespace, name, fields).fold(
            onSuccess = { cambio ->
                if (!cambio.unchanged) schemaChangeNotifier.notificar(cambio, namespace)
                ResponseEntity.ok(respuestaDe(cambio))
            },
            onFailure = { error -> errorDeRegistro(error) }
        )
    }

    /**
     * Cuerpo de la respuesta de un cambio de schema.
     *
     * `subscriptionsOnPreviousVersion` es el dato que convierte un cambio incompatible en
     * algo consciente: quien lo hizo ve en el acto a cuántos consumidores dejó atrás.
     */
    private fun respuestaDe(cambio: CambioDeEsquema): Map<String, Any?> = mapOf(
        "fqn" to cambio.fqn,
        "topic" to cambio.topic,
        "version" to cambio.version,
        "schemaId" to cambio.schemaId,
        "breaking" to cambio.breaking,
        "unchanged" to cambio.unchanged,
        "previousTopic" to cambio.previousTopic,
        "subscriptionsOnPreviousVersion" to
            cambio.previousTopic?.let { subscriptionService.suscriptoresA(listOf(it)).size }
    )

    @Operation(
        summary = "Borrar un event type",
        description = """Borra el event type **entero y para siempre**: todas sus versiones
mayores, sus tópicos de Kafka con los eventos que contengan, y sus subjects del Schema
Registry.

Después de esto el nombre queda libre y se puede volver a registrar con cualquier schema.

Se rechaza con 409 si hay equipos **ajenos** suscriptos: cortarles la entrega sin que se
enteren no es una decisión que le corresponda a otro grupo. Las suscripciones propias sí
se dan de baja solas, porque un webhook a un tópico que ya no existe no vuelve a entregar
nada.

Para retirar sólo una versión vieja está `DELETE /api/v1/event-types/{fqn}/versions/{n}`.""",
        responses = [
            ApiResponse(
                responseCode = "200", description = "Event type borrado",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Borrado",
                        value = """{
  "fqn": "com.citypass.movilidad.BiciDevuelta",
  "deletedTopics": [
    "com.citypass.movilidad.BiciDevuelta",
    "com.citypass.movilidad.BiciDevuelta.v2"
  ],
  "subscriptionsRemoved": 1
}"""
                    )]
                )]
            ),
            ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
            ApiResponse(responseCode = "403", description = "El event type es de otro equipo"),
            ApiResponse(responseCode = "404", description = "Event type no encontrado"),
            ApiResponse(responseCode = "409", description = "Hay equipos ajenos suscriptos"),
            ApiResponse(responseCode = "502", description = "No se pudo completar el borrado")
        ],
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @DeleteMapping("/{fqn}")
    fun delete(
        @PathVariable fqn: String,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<Any> {
        if (jwt == null) return sinToken()
        val namespace = jwt.claims["namespace"] as? String ?: return sinNamespace()
        if (!topicAuthorizationService.isAllowed(jwt, fqn)) return ajeno(fqn, "borrar")

        val topicos = schemaRegistryService.topicosDeEventType(fqn)
        ajenosSuscriptos(topicos, namespace)?.let { return it }

        return schemaRegistryService.deleteEventType(fqn).fold(
            onSuccess = { borrados ->
                ResponseEntity.ok(mapOf(
                    "fqn" to fqn,
                    "deletedTopics" to borrados,
                    "subscriptionsRemoved" to subscriptionService.unregisterTopics(borrados)
                ))
            },
            onFailure = { error -> errorDeBorrado(error) }
        )
    }

    @Operation(
        summary = "Borrar una versión vieja de un event type",
        description = """Retira una versión mayor que ya nadie usa: borra su tópico y su
subject, y deja el resto del event type intacto.

Es el camino de limpieza para que las versiones no se acumulen. No se puede borrar la
versión vigente —dejaría al event type sin dónde publicar—; para eso está el borrado del
event type completo.""",
        responses = [
            ApiResponse(
                responseCode = "200", description = "Versión borrada",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Borrada",
                        value = """{
  "fqn": "com.citypass.movilidad.BiciDevuelta",
  "deletedTopics": ["com.citypass.movilidad.BiciDevuelta"],
  "subscriptionsRemoved": 0
}"""
                    )]
                )]
            ),
            ApiResponse(responseCode = "400", description = "La versión es la vigente"),
            ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
            ApiResponse(responseCode = "403", description = "El event type es de otro equipo"),
            ApiResponse(responseCode = "404", description = "No existe esa versión"),
            ApiResponse(responseCode = "409", description = "Hay equipos ajenos suscriptos"),
            ApiResponse(responseCode = "502", description = "No se pudo completar el borrado")
        ],
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @DeleteMapping("/{fqn}/versions/{version}")
    fun deleteVersion(
        @PathVariable fqn: String,
        @PathVariable version: Int,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<Any> {
        if (jwt == null) return sinToken()
        val namespace = jwt.claims["namespace"] as? String ?: return sinNamespace()
        if (!topicAuthorizationService.isAllowed(jwt, fqn)) return ajeno(fqn, "borrar")

        val topico = schemaRegistryService.topicoDe(fqn, version)
        ajenosSuscriptos(listOf(topico), namespace)?.let { return it }

        return schemaRegistryService.deleteVersion(fqn, version).fold(
            onSuccess = { borrado ->
                ResponseEntity.ok(mapOf(
                    "fqn" to fqn,
                    "deletedTopics" to listOf(borrado),
                    "subscriptionsRemoved" to subscriptionService.unregisterTopics(listOf(borrado))
                ))
            },
            onFailure = { error -> errorDeBorrado(error) }
        )
    }

    // ─────────────────────────── Respuestas compartidas ───────────────────────────

    /**
     * 409 si algún equipo ajeno está suscripto a los tópicos que se van a borrar.
     *
     * Se nombra a los dueños y sus tópicos: sin eso, quien recibe el rechazo no tiene con
     * quién hablar para coordinar la baja.
     *
     * @return El problem detail a devolver, o null si se puede borrar.
     */
    private fun ajenosSuscriptos(topicos: List<String>, namespace: String): ResponseEntity<Any>? {
        val ajenos = subscriptionService.suscriptoresA(topicos).filter { it.owner != namespace }
        if (ajenos.isEmpty()) return null
        return problem(
            HttpStatus.CONFLICT, "Hay equipos suscriptos",
            "No se puede borrar porque ${ajenos.size} suscripción(es) de otros equipos siguen " +
                "recibiendo estos eventos. Coordiná la baja con ellos.",
            mapOf("subscribers" to ajenos.map { mapOf("owner" to it.owner, "topic" to it.topic) })
        )
    }

    private fun camposDe(request: Map<String, Any>): List<Any>? {
        @Suppress("UNCHECKED_CAST")
        return request["fields"] as? List<Any>
    }

    private fun faltanCampos(): ResponseEntity<Any> = problem(
        HttpStatus.BAD_REQUEST, "Falta un campo requerido",
        "El campo 'fields' es obligatorio y debe ser un array JSON."
    )

    private fun sinToken(): ResponseEntity<Any> = problem(
        HttpStatus.UNAUTHORIZED, "Autenticación requerida",
        "Este endpoint requiere un token JWT válido."
    )

    private fun sinNamespace(): ResponseEntity<Any> = problem(
        HttpStatus.BAD_REQUEST, "Token sin namespace",
        "El token JWT no contiene el claim 'namespace'."
    )

    private fun ajeno(fqn: String, accion: String): ResponseEntity<Any> = problem(
        HttpStatus.FORBIDDEN, "Event type de otro equipo",
        "Sólo el equipo dueño de '$fqn' puede $accion su schema."
    )

    /** Un FQN inexistente es 404; un schema mal formado, 400; lo demás es del registry. */
    private fun errorDeRegistro(error: Throwable): ResponseEntity<Any> = when (error) {
        // No es un error del pedido: el pedido está bien y el sistema no tiene lugar.
        // Se arregla borrando algo, no corrigiendo el request.
        is CupoAgotadoException ->
            problem(HttpStatus.CONFLICT, "Cupo de event types agotado", error.descripcion())
        is NoSuchElementException ->
            problem(HttpStatus.NOT_FOUND, "Event type no encontrado", error.descripcion())
        is IllegalArgumentException ->
            problem(HttpStatus.BAD_REQUEST, "Event type inválido", error.descripcion())
        else ->
            problem(HttpStatus.BAD_GATEWAY, "Error del Schema Registry", error.descripcion())
    }

    private fun errorDeBorrado(error: Throwable): ResponseEntity<Any> = when (error) {
        is NoSuchElementException ->
            problem(HttpStatus.NOT_FOUND, "Event type no encontrado", error.descripcion())
        is IllegalStateException ->
            problem(HttpStatus.BAD_REQUEST, "No se puede borrar esa versión", error.descripcion())
        else ->
            problem(HttpStatus.BAD_GATEWAY, "Error al borrar", error.descripcion())
    }

    /**
     * Texto de una excepción.
     *
     * `Throwable.message` es `String?`, y todas las que llegan acá lo traen: un `?:` en
     * cada uso sería una rama que ningún test puede alcanzar.
     */
    private fun Throwable.descripcion(): String = message.orEmpty()
}
