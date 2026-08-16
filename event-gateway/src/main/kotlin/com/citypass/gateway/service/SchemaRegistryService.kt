package com.citypass.gateway.service

import jakarta.annotation.PostConstruct
import org.apache.avro.Schema
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.core.annotation.Order
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/**
 * Un event type resuelto y listo para publicar.
 *
 * Existe para que quien publica obtenga tópico, schema y schemaId en una sola consulta.
 * Devolverlos por separado obligaría a resolver la versión vigente tres veces, o a
 * afirmar con `!!` que lo hallado la primera vez sigue estando.
 *
 * @param topic Tópico Kafka de la versión vigente.
 * @param schema Schema Avro de esa versión.
 * @param schemaId Id en el Schema Registry, o null si todavía no se pudo registrar.
 */
data class TipoResuelto(val topic: String, val schema: Schema, val schemaId: Int?)

/**
 * Resultado de actualizar el schema de un event type.
 *
 * @param fqn Nombre lógico del event type, que no cambia nunca.
 * @param topic Tópico donde quedaron los eventos nuevos.
 * @param version Versión mayor vigente después del cambio.
 * @param schemaId Id que el Schema Registry le asignó al schema nuevo.
 * @param breaking Si el cambio fue incompatible y por lo tanto estrenó versión.
 * @param previousTopic Tópico de la versión anterior; null si el cambio fue compatible.
 * @param unchanged Si el schema enviado era idéntico al vigente y no se hizo nada.
 */
data class CambioDeEsquema(
    val fqn: String,
    val topic: String,
    val version: Int,
    val schemaId: Int,
    val breaking: Boolean,
    val previousTopic: String?,
    val unchanged: Boolean
)

/**
 * Servicio central de gestión de schemas Avro.
 *
 * Responsabilidades:
 * - Cargar schemas desde archivos .avsc al arrancar y asegurar sus tópicos Kafka.
 * - Registrar schemas en Confluent Schema Registry (con reintentos).
 * - Decidir, consultando al registry, si un cambio de schema es compatible.
 * - Resolver el tópico vigente de un event type y los schemas por id.
 * - Borrar versiones o event types completos.
 *
 * ## Nombre lógico y versiones
 *
 * Un event type tiene un **nombre lógico** estable —el FQN `namespace.Name`— y una o más
 * **versiones mayores**, cada una con su tópico Kafka y su subject en el registry. La
 * versión mayor sólo avanza cuando un cambio es incompatible; los cambios compatibles
 * evolucionan dentro del mismo subject y no mueven nada.
 *
 * La versión 1 **no lleva sufijo**: su tópico es el FQN pelado. Así, un event type que
 * nunca se rompió se ve exactamente igual que antes de que existiera el versionado, y un
 * sufijo en un nombre significa siempre lo mismo: ahí hubo una ruptura de contrato.
 *
 * No hay ambigüedad posible entre un sufijo y un FQN real: `namespace` debe ser todo
 * minúsculas y `name` no puede tener la forma `v<número>` (ver [validateNewSchema]).
 *
 * ## Índices en memoria
 *
 * - [schemas]: **tópico** → Schema.
 * - [schemaIds]: **tópico** → registryId, para el header Confluent.
 * - [schemasByRegistryId]: registryId → Schema, para deserializar sin búsqueda lineal.
 * - [vigentes]: FQN → tópico de la versión mayor más alta.
 *
 * Los cuatro se derivan de los archivos .avsc del disco, que son la única fuente
 * durable: el nombre del archivo es el tópico y la versión sale de ese nombre, así que
 * no hay ningún índice persistido que pueda quedar desincronizado.
 *
 * @param restClient Cliente HTTP para comunicarse con el Schema Registry.
 * @param kafkaTopicAdmin Borrado de tópicos, aislado porque habla con el broker real.
 * @param schemasDir Directorio donde se almacenan los archivos .avsc (variable de entorno SCHEMAS_DIR).
 * @param schemaRegistryUrl URL del Confluent Schema Registry (variable de entorno SCHEMA_REGISTRY_URL).
 */
@Service
class SchemaRegistryService(
    private val restClient: RestClient,
    private val kafkaAdmin: KafkaAdmin,
    private val kafkaTopicAdmin: KafkaTopicAdmin,
    @Value("\${gateway.schemas-dir}") private val schemasDir: String,
    @Value("\${gateway.schema-registry-url}") private val schemaRegistryUrl: String,
    @Value("\${gateway.topic-partitions}") private val topicPartitions: Int,
    @Value("\${gateway.topic-replication-factor}") private val topicReplicationFactor: Int
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    private val schemas = mutableMapOf<String, Schema>()
    private val schemaIds = mutableMapOf<String, Int>()
    private val schemasByRegistryId = mutableMapOf<Int, Schema>()
    private val vigentes = mutableMapOf<String, String>()

    private val namePattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /** Sufijo de versión mayor de un tópico. La v1 no lo lleva. */
    private val sufijoDeVersion = Regex("^(.+)\\.v(\\d+)$")

    /** Un `name` con esta forma chocaría con el sufijo de versión. */
    private val nameReservado = Regex("^v\\d+$")
    private val namespacePattern = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$")

    private companion object {
        /** Los dos campos del envelope que envuelve todo schema de evento. */
        const val METADATA_FIELD = "metadata"
        const val DATA_FIELD = "data"
    }

    /**
     * Schema de la metadata, definido una única vez en `resources/avro/event-metadata.avsc`.
     *
     * Los schemas de evento lo referencian solo por nombre; al serializarlos, Avro expande
     * la definición completa, de modo que lo que llega al Schema Registry y a los
     * consumidores es siempre un schema autosuficiente.
     */
    private val metadataSchema: Schema =
        Schema.Parser().parse(ClassPathResource("avro/event-metadata.avsc").inputStream)

    /** Schema de la metadata que el gateway inyecta en todo evento. */
    fun getMetadataSchema(): Schema = metadataSchema

    // ─────────────────────────── Nombres y versiones ───────────────────────────

    /**
     * Tópico de una versión mayor.
     *
     * La v1 no lleva sufijo, así que su tópico es el FQN. Ver la nota de la clase.
     */
    internal fun topicoDe(fqn: String, version: Int): String =
        if (version == 1) fqn else "$fqn.v$version"

    /**
     * Descompone un tópico en su nombre lógico y su versión mayor.
     *
     * Un tópico sin sufijo es la versión 1.
     */
    internal fun versionDe(topico: String): Pair<String, Int> {
        val sufijo = sufijoDeVersion.find(topico) ?: return topico to 1
        return sufijo.groupValues[1] to sufijo.groupValues[2].toInt()
    }

    /**
     * Registra un schema en los cuatro índices.
     *
     * Punto único de escritura: [vigentes] es un índice derivado, y calcularlo acá evita
     * que quede desactualizado si alguien agrega otro camino que inserte en [schemas].
     */
    private fun indexar(topico: String, schema: Schema) {
        schemas[topico] = schema
        val (fqn, version) = versionDe(topico)
        val vigente = vigentes[fqn]
        if (vigente == null || versionDe(vigente).second < version) vigentes[fqn] = topico
    }

    /** Quita un tópico de los índices y recalcula la versión vigente de su event type. */
    private fun desindexar(topico: String) {
        schemas.remove(topico)
        schemaIds.remove(topico)
        val (fqn, _) = versionDe(topico)
        vigentes.remove(fqn)
        schemas.keys
            .filter { versionDe(it).first == fqn }
            .forEach { indexar(it, schemas.getValue(it)) }
    }

    // ─────────────────────────── Carga y arranque ───────────────────────────

    /**
     * Carga todos los archivos .avsc del directorio de schemas al arrancar.
     *
     * El tópico **es el nombre del archivo**, no el `fullName` del schema: dos versiones
     * mayores del mismo event type comparten el `fullName` —el record se sigue llamando
     * igual— y sólo se distinguen por el sufijo del archivo.
     */
    @PostConstruct
    fun loadSchemas() {
        val dir = File(schemasDir)
        if (!dir.exists() || !dir.isDirectory) {
            logger.warn("Schemas directory not found: $schemasDir")
            return
        }
        dir.listFiles { f -> f.extension == "avsc" }.orEmpty().sortedBy { it.name }.forEach { file ->
            val topico = file.nameWithoutExtension
            val schema = Schema.Parser().parse(file)
            val (fqn, version) = versionDe(topico)

            // Un archivo renombrado a mano dejaría un event type publicando en un tópico
            // que no le corresponde. Se descarta en vez de cargarlo mal.
            if (schema.fullName != fqn) {
                logger.error(
                    "El archivo ${file.name} declara el record '${schema.fullName}' pero su " +
                        "nombre indica el event type '$fqn'. Se ignora."
                )
                return@forEach
            }

            indexar(topico, schema)
            logger.info("Loaded schema: $topico (v$version)")
        }

        // Los schemas anteriores al envelope siguen resolviendo por FQN, pero publicar
        // en ellos falla. Se avisa acá y no recién al primer POST para que el problema
        // aparezca al arrancar, con la lista completa de los que hay que re-registrar.
        val legacy = schemas.filterValues { it.getField(DATA_FIELD) == null }.keys
        if (legacy.isNotEmpty())
            logger.warn(
                "Schemas con el formato anterior (campos planos): ${legacy.joinToString(", ")}. " +
                "No se puede publicar en ellos; hay que volver a registrarlos para que usen " +
                "el envelope metadata/data."
            )
    }

    /**
     * Crea el tópico Kafka de una versión de un event type.
     *
     * Se crea al registrar y no al publicar el primer evento: es el momento en que se
     * declara el contrato, y permite fijar particiones y réplicas en vez de heredar
     * los defaults del broker.
     */
    private fun createTopic(topico: String) {
        kafkaAdmin.createOrModifyTopics(
            NewTopic(topico, topicPartitions, topicReplicationFactor.toShort())
        )
        logger.info("Ensured Kafka topic $topico ($topicPartitions particiones)")
    }

    /**
     * Asegura tópico y schema de todo lo cargado del disco, al arrancar.
     *
     * Se ejecuta cuando la aplicación está lista, para darle tiempo a Kafka y al
     * Schema Registry. La creación del tópico es idempotente, así que en el caso
     * normal no hace nada: importa cuando el volumen de Kafka se recreó y los .avsc
     * sobrevivieron, porque entonces hay event types registrados sin tópico donde
     * publicar.
     *
     * Un fallo al crear un tópico no corta el arranque ni saltea el registro del
     * schema: se deja anotado y el resto sigue.
     */
    @EventListener(ApplicationReadyEvent::class)
    @Order(1)
    fun registerSchemas() {
        schemas.forEach { (topico, schema) ->
            try {
                createTopic(topico)
            } catch (e: Exception) {
                logger.error("No se pudo asegurar el tópico $topico al arrancar: ${e.message}")
            }
            registerWithRetry(topico, schema)
        }
    }

    /**
     * Registra un schema en el Schema Registry con reintentos.
     *
     * @param topico Tópico de la versión, que también da el nombre del subject.
     * @param schema Schema Avro a registrar.
     * @param maxRetries Cantidad máxima de intentos (default 10).
     */
    internal fun registerWithRetry(
        topico: String,
        schema: Schema,
        maxRetries: Int = 10,
        retryDelayMs: Long = 3000
    ) {
        repeat(maxRetries) { attempt ->
            try {
                val id = postSchemaToRegistry(topico, schema)
                schemaIds[topico] = id
                schemasByRegistryId[id] = schema
                logger.info("Registered schema $topico with ID: $id")
                return
            } catch (e: Exception) {
                logger.warn("Schema registration attempt ${attempt + 1}/$maxRetries for $topico failed: ${e.message}")
                if (attempt < maxRetries - 1) Thread.sleep(retryDelayMs)
            }
        }
        logger.error("Failed to register schema for $topico after $maxRetries attempts")
    }

    // ─────────────────────────── Consulta ───────────────────────────

    /**
     * Resuelve dónde publicar un event type.
     *
     * Acepta el **nombre lógico** —que es lo que debería usar todo productor, y que rutea
     * a la versión vigente— y también un tópico de versión explícito, que sirve para
     * seguir alimentando una versión vieja durante una migración.
     *
     * @param nombre FQN del event type, o tópico de una versión concreta.
     * @return El tópico, su schema y su schemaId, o null si no existe ninguno de los dos.
     */
    fun resolver(nombre: String): TipoResuelto? {
        val topico = vigentes[nombre] ?: nombre
        val schema = schemas[topico] ?: return null
        return TipoResuelto(topico, schema, schemaIds[topico])
    }

    /**
     * Obtiene el schema Avro de un tópico.
     *
     * @param topico Tópico de la versión. Para un event type sin rupturas es su FQN.
     */
    fun getSchema(topico: String): Schema? = schemas[topico]

    /**
     * Obtiene el ID numérico del schema de un tópico en el Schema Registry.
     */
    fun getSchemaId(topico: String): Int? = schemaIds[topico]

    /**
     * Nombres lógicos de todos los event types registrados.
     *
     * Se devuelven los nombres y no los tópicos porque es la lista que le sirve a quien
     * publica: las versiones son un detalle del que un productor no tiene que enterarse.
     */
    fun getAvailableEventTypes(): Set<String> = vigentes.keys

    /**
     * Todos los tópicos de un namespace, incluidas las versiones viejas.
     *
     * Lo usa la lectura de eventos, que sí tiene que mirar el historial completo.
     */
    fun topicosDeNamespace(namespace: String): List<String> =
        schemas.filterValues { it.namespace == namespace }.keys.sorted()

    /**
     * Tópicos de todas las versiones de un event type, de la más vieja a la más nueva.
     *
     * Es lo que hay que borrar para que el nombre quede libre, y lo que hay que mirar
     * para saber a quién afectaría ese borrado.
     */
    fun topicosDeEventType(fqn: String): List<String> =
        schemas.keys.filter { versionDe(it).first == fqn }.sortedBy { versionDe(it).second }

    /**
     * Versiones de un event type, de la más vieja a la más nueva.
     */
    fun versionesDe(fqn: String): List<Map<String, Any?>> =
        topicosDeEventType(fqn).map {
            mapOf(
                "version" to versionDe(it).second,
                "topic" to it,
                "schemaId" to schemaIds[it]
            )
        }

    /**
     * Resumen de cada event type registrado, opcionalmente acotado a un namespace.
     *
     * Se lista un elemento por **nombre lógico**, con sus versiones adentro. Listar una
     * fila por tópico haría parecer que un event type que se rompió una vez son dos
     * event types distintos.
     *
     * @param namespace Si no es null, filtra por namespace exacto.
     */
    fun listEventTypes(namespace: String?): List<Map<String, Any?>> =
        vigentes.entries
            .map { it.key to schemas.getValue(it.value) }
            .filter { (_, schema) -> namespace == null || schema.namespace == namespace }
            .sortedBy { (fqn, _) -> fqn }
            .map { (fqn, schema) ->
                val topico = vigentes.getValue(fqn)
                mapOf(
                    "fqn" to fqn,
                    "namespace" to schema.namespace,
                    "name" to schema.name,
                    "topic" to topico,
                    "version" to versionDe(topico).second,
                    "schemaId" to schemaIds[topico],
                    "versions" to versionesDe(fqn)
                )
            }

    // ─────────────────────────── Alta ───────────────────────────

    /**
     * Construye el envelope de un event type a partir de los campos de negocio.
     *
     * El schema resultante es un record de dos campos:
     * - `data`: un record con los campos del productor, en el namespace `<namespace>.data`.
     * - `metadata`: el record [getMetadataSchema], que calcula íntegramente el gateway.
     *
     * Al vivir en records separados, los datos de negocio no pueden pisar la metadata
     * de auditoría, así que no hacen falta nombres reservados: un productor puede
     * declarar un campo llamado `source` o `eventId` sin conflicto.
     */
    private fun construirSchema(namespace: String, name: String, userFields: List<Any>): Result<Schema> {
        val schemaMap = mapOf(
            "type" to "record",
            "name" to name,
            "namespace" to namespace,
            // `data` va primero: es lo que le importa a quien lee el evento. La metadata
            // es el sobre y queda debajo.
            "fields" to listOf(
                mapOf(
                    "name" to DATA_FIELD,
                    "type" to mapOf(
                        "type" to "record",
                        "name" to name,
                        "namespace" to "$namespace.data",
                        "fields" to userFields
                    )
                ),
                mapOf("name" to METADATA_FIELD, "type" to metadataSchema.fullName)
            )
        )

        // El parser se siembra con EventMetadata para resolver la referencia por nombre.
        // Debe ser una instancia nueva en cada registro: Schema.Parser acumula los tipos
        // que ya vio y rechaza redefinirlos, así que uno compartido fallaría al re-registrar.
        return try {
            Result.success(
                Schema.Parser()
                    .apply { addTypes(listOf(metadataSchema)) }
                    .parse(mapper.writeValueAsString(schemaMap))
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Schema Avro inválido: ${e.message}"))
        }
    }

    /**
     * Registra un event type nuevo, en su versión 1.
     *
     * @param namespace Namespace Avro del equipo emisor (ej: "com.citypass.movilidad").
     * @param name Nombre del record Avro (ej: "BiciDevuelta").
     * @param userFields Lista de definiciones de campos de negocio del productor.
     * @return Result con el ID del schema si fue exitoso, o la excepción si falló.
     */
    fun registerNewSchema(namespace: String, name: String, userFields: List<Any>): Result<Int> {
        val validationError = validateNewSchema(namespace, name)
        if (validationError != null) return Result.failure(IllegalArgumentException(validationError))

        val schema = construirSchema(namespace, name, userFields).getOrElse { return Result.failure(it) }
        val fqn = schema.fullName

        if (vigentes.containsKey(fqn))
            return Result.failure(
                IllegalArgumentException(
                    "Ya existe un event type registrado para '$fqn'. Para cambiarle el schema " +
                        "usá PUT /api/v1/event-types/$fqn."
                )
            )

        try {
            createTopic(fqn)
        } catch (e: Exception) {
            logger.error("Failed to create Kafka topic for $fqn: ${e.message}")
            return Result.failure(RuntimeException("No se pudo crear el tópico Kafka: ${e.message}"))
        }

        return try {
            val id = publicarVersion(fqn, schema)
            logger.info("Registered new event type $fqn with ID: $id")
            Result.success(id)
        } catch (e: Exception) {
            logger.error("Failed to register schema for $fqn: ${e.message}")
            Result.failure(RuntimeException("Failed to register schema in Schema Registry: ${e.message}"))
        }
    }

    /**
     * Registra un schema en el registry, lo indexa y lo persiste en disco.
     *
     * Los tres pasos van juntos porque un schema que quedara en memoria sin archivo
     * desaparecería en el próximo arranque, y uno en disco sin id no se puede publicar.
     */
    private fun publicarVersion(topico: String, schema: Schema): Int {
        val id = postSchemaToRegistry(topico, schema)
        indexar(topico, schema)
        schemaIds[topico] = id
        schemasByRegistryId[id] = schema
        File(schemasDir, "$topico.avsc").writeText(schema.toString(true))
        return id
    }

    // ─────────────────────────── Cambio de schema ───────────────────────────

    /**
     * Cambia el schema de un event type existente.
     *
     * Quién decide qué pasa no es el llamador sino el Schema Registry:
     *
     * - **Schema idéntico** al vigente: no se hace nada. Un PUT repetido no debe ir
     *   acumulando versiones iguales en el registry.
     * - **Cambio compatible** (agregar un campo con default, ensanchar un `int` a `long`):
     *   se registra como una versión más del mismo subject. Mismo tópico, mismas
     *   suscripciones, ningún consumidor se entera.
     * - **Cambio incompatible**: estrena versión mayor, con tópico y subject nuevos. La
     *   versión anterior queda intacta, sirviendo su historial, para que los consumidores
     *   migren cuando puedan.
     *
     * Que la compatibilidad la determine el registry y no un parámetro del request es
     * deliberado: un equipo que tuviera que declarar si su cambio rompe podría declararlo
     * mal, y el error se descubriría del lado de los consumidores.
     *
     * @param namespace Namespace del equipo, tomado del JWT.
     * @param name Nombre del record. No puede cambiar: junto al namespace forma el FQN.
     * @param userFields Campos de negocio nuevos, completos — reemplazan a los anteriores.
     */
    fun updateSchema(namespace: String, name: String, userFields: List<Any>): Result<CambioDeEsquema> {
        val validationError = validateNewSchema(namespace, name)
        if (validationError != null) return Result.failure(IllegalArgumentException(validationError))

        val nuevo = construirSchema(namespace, name, userFields).getOrElse { return Result.failure(it) }
        val fqn = nuevo.fullName

        val topicoActual = vigentes[fqn]
            ?: return Result.failure(NoSuchElementException("No hay ningún event type registrado con el FQN '$fqn'."))

        val version = versionDe(topicoActual).second

        if (schemas.getValue(topicoActual) == nuevo)
            return Result.success(
                CambioDeEsquema(
                    fqn = fqn, topic = topicoActual, version = version,
                    // Un schema idéntico sólo puede estar registrado: si no lo estuviera,
                    // no habría con qué compararlo.
                    schemaId = schemaIds.getValue(topicoActual),
                    breaking = false, previousTopic = null, unchanged = true
                )
            )

        val compatible = esCompatible(topicoActual, nuevo).getOrElse { return Result.failure(it) }

        return try {
            if (compatible) {
                val id = publicarVersion(topicoActual, nuevo)
                logger.info("Evolved schema $topicoActual compatibly, new ID: $id")
                Result.success(
                    CambioDeEsquema(
                        fqn = fqn, topic = topicoActual, version = version, schemaId = id,
                        breaking = false, previousTopic = null, unchanged = false
                    )
                )
            } else {
                val nuevoTopico = topicoDe(fqn, version + 1)
                createTopic(nuevoTopico)
                val id = publicarVersion(nuevoTopico, nuevo)
                logger.warn("Breaking schema change on $fqn: new major version $nuevoTopico (ID $id)")
                Result.success(
                    CambioDeEsquema(
                        fqn = fqn, topic = nuevoTopico, version = version + 1, schemaId = id,
                        breaking = true, previousTopic = topicoActual, unchanged = false
                    )
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to update schema for $fqn: ${e.message}")
            Result.failure(RuntimeException("Failed to register schema in Schema Registry: ${e.message}"))
        }
    }

    /**
     * Le pregunta al Schema Registry si un schema es compatible con el vigente del subject.
     *
     * Un fallo de la consulta se propaga en vez de asumir un valor. Asumir "compatible"
     * rompería consumidores sin aviso; asumir "incompatible" estrenaría una versión mayor
     * —y con ella una migración para todo el mundo— por un problema de red.
     */
    private fun esCompatible(topico: String, schema: Schema): Result<Boolean> = try {
        val response = restClient.post()
            .uri("$schemaRegistryUrl/compatibility/subjects/$topico-value/versions/latest")
            .contentType(MediaType.parseMediaType("application/vnd.schemaregistry.v1+json"))
            .body(mapOf("schema" to schema.toString()))
            .retrieve()
            .body(Map::class.java)
        // Una respuesta sin cuerpo, o sin el campo, se toma como incompatible: es la
        // opción que no rompe consumidores sin avisar.
        Result.success(response != null && response["is_compatible"] == true)
    } catch (e: Exception) {
        logger.error("No se pudo consultar compatibilidad de $topico: ${e.message}")
        Result.failure(
            RuntimeException(
                "El Schema Registry no pudo determinar si el cambio es compatible: ${e.message}. " +
                    "No se cambió nada."
            )
        )
    }

    // ─────────────────────────── Borrado ───────────────────────────

    /**
     * Borra un event type entero: todas sus versiones, sus tópicos y sus subjects.
     *
     * Es permanente y libera el nombre, que puede volver a registrarse después con
     * cualquier schema.
     *
     * @return Los tópicos borrados, o falla si el FQN no existe o si el borrado remoto no
     *         se pudo completar.
     */
    fun deleteEventType(fqn: String): Result<List<String>> {
        if (!vigentes.containsKey(fqn))
            return Result.failure(NoSuchElementException("No hay ningún event type registrado con el FQN '$fqn'."))

        val topicos = topicosDeEventType(fqn)
        return borrar(topicos).map { topicos }
    }

    /**
     * Borra una versión mayor concreta de un event type.
     *
     * No se puede borrar la vigente: dejaría al event type existiendo sin dónde publicar.
     * Para eso está [deleteEventType], que lo borra entero.
     */
    fun deleteVersion(fqn: String, version: Int): Result<String> {
        val vigente = vigentes[fqn]
            ?: return Result.failure(NoSuchElementException("No hay ningún event type registrado con el FQN '$fqn'."))

        val topico = topicoDe(fqn, version)
        if (!schemas.containsKey(topico))
            return Result.failure(NoSuchElementException("El event type '$fqn' no tiene una versión $version."))

        if (topico == vigente)
            return Result.failure(
                IllegalStateException(
                    "La versión $version es la vigente de '$fqn' y no se puede borrar sola. " +
                        "Para dar de baja el event type completo usá DELETE /api/v1/event-types/$fqn."
                )
            )

        return borrar(listOf(topico)).map { topico }
    }

    /**
     * Borra tópicos y subjects, y recién después el estado local.
     *
     * El orden importa: si el borrado remoto falla, el event type sigue existiendo entero
     * y se puede reintentar. Al revés quedaría un tópico huérfano que nadie sabe que está.
     */
    private fun borrar(topicos: List<String>): Result<Unit> = try {
        topicos.forEach { borrarSubject(it) }
        kafkaTopicAdmin.borrar(topicos)
        topicos.forEach {
            desindexar(it)
            File(schemasDir, "$it.avsc").delete()
            logger.warn("Deleted event type version $it")
        }
        Result.success(Unit)
    } catch (e: Exception) {
        logger.error("Failed to delete ${topicos.joinToString(", ")}: ${e.message}")
        Result.failure(RuntimeException("No se pudo completar el borrado: ${e.message}. No se cambió nada."))
    }

    /**
     * Borra el subject del registry de forma permanente.
     *
     * Confluent exige dos pasos: el borrado suave lo saca de circulación y el permanente
     * libera de verdad el nombre y la numeración de versiones. Sin el segundo, volver a
     * registrar el mismo nombre heredaría la numeración vieja.
     *
     * Un subject que no existe se toma como ya borrado, para que reintentar un borrado que
     * falló a mitad de camino funcione.
     */
    private fun borrarSubject(topico: String) {
        borrarTolerandoAusencia("$schemaRegistryUrl/subjects/$topico-value")
        borrarTolerandoAusencia("$schemaRegistryUrl/subjects/$topico-value?permanent=true")
    }

    private fun borrarTolerandoAusencia(uri: String) {
        try {
            restClient.delete().uri(uri).retrieve().toBodilessEntity()
        } catch (e: RestClientResponseException) {
            if (e.statusCode != HttpStatus.NOT_FOUND) throw e
            logger.info("El subject ya no estaba en el registry: $uri")
        }
    }

    // ─────────────────────────── Schema Registry ───────────────────────────

    /**
     * Resuelve un schema Avro por su ID numérico del Schema Registry.
     *
     * Primero busca en el índice local (O(1)). Si no lo encuentra,
     * consulta al Schema Registry via HTTP como fallback y cachea el resultado.
     *
     * @param id ID numérico del schema en el registry.
     * @return Schema Avro o null si no se pudo resolver.
     */
    fun getSchemaById(id: Int): Schema? {
        schemasByRegistryId[id]?.let { return it }

        return try {
            val response = restClient.get()
                .uri("$schemaRegistryUrl/schemas/ids/$id")
                .retrieve()
                .body(Map::class.java)
                ?: return null
            val schemaStr = response["schema"] as? String ?: return null
            val schema = Schema.Parser().parse(schemaStr)
            schemasByRegistryId[id] = schema
            schema
        } catch (e: Exception) {
            logger.warn("Could not fetch schema ID $id from registry: ${e.message}")
            null
        }
    }

    /**
     * Registra un schema en el Schema Registry de Confluent via HTTP POST.
     *
     * @param topico Tópico de la versión — se usa como subject con sufijo "-value".
     * @param schema Schema Avro a registrar.
     * @return ID numérico asignado por el Schema Registry.
     * @throws Exception Si la llamada HTTP falla.
     */
    private fun postSchemaToRegistry(topico: String, schema: Schema): Int {
        val response = restClient.post()
            .uri("$schemaRegistryUrl/subjects/$topico-value/versions")
            .contentType(MediaType.parseMediaType("application/vnd.schemaregistry.v1+json"))
            .body(mapOf("schema" to schema.toString()))
            .retrieve()
            .body(Map::class.java)
        return (response!!["id"] as Number).toInt()
    }

    /**
     * Valida namespace y name antes de construir el schema.
     *
     * Los campos del productor no necesitan validación de nombres reservados: viven en
     * su propio record `data`, separado de la metadata del gateway.
     *
     * @return Mensaje de error si la validación falla, null si es válido.
     */
    private fun validateNewSchema(namespace: String, name: String): String? {
        if (!namePattern.matches(name))
            return "El name debe ser un identificador Avro válido (letras, dígitos y _). Ejemplo: BiciDevuelta"

        // Un event type llamado `v2` haría que su FQN se leyera como la versión 2 de otro.
        if (nameReservado.matches(name))
            return "El name '$name' está reservado: la forma v<número> identifica versiones de un event type."

        if (!namespacePattern.matches(namespace))
            return "El namespace debe seguir el formato de paquete inverso en minúsculas. Ejemplo: com.citypass.movilidad"

        return null
    }
}
