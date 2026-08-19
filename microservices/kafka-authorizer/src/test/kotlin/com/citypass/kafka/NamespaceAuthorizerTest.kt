package com.citypass.kafka

import org.apache.kafka.common.Endpoint
import org.apache.kafka.common.acl.AclBindingFilter
import org.apache.kafka.common.acl.AclOperation
import org.apache.kafka.common.resource.PatternType
import org.apache.kafka.common.resource.ResourcePattern
import org.apache.kafka.common.resource.ResourceType
import org.apache.kafka.common.security.auth.KafkaPrincipal
import org.apache.kafka.common.security.auth.SecurityProtocol
import org.apache.kafka.server.authorizer.Action
import org.apache.kafka.server.authorizer.AuthorizableRequestContext
import org.apache.kafka.server.authorizer.AuthorizationResult
import org.apache.kafka.server.authorizer.AuthorizerServerInfo
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NamespaceAuthorizerTest {

    private val authorizer = NamespaceAuthorizer().apply { configure(mutableMapOf<String, Any>()) }

    @AfterTest fun cleanup() = authorizer.close()

    private val movilidad = "com.citypass.movilidad"
    private val reclamos  = "com.citypass.reclamos"

    /** Contexto mínimo: el autorizador sólo mira el principal. */
    private fun contextOf(principalName: String) = object : AuthorizableRequestContext {
        override fun listenerName() = "EXTERNAL"
        override fun securityProtocol() = SecurityProtocol.SASL_PLAINTEXT
        override fun principal() = KafkaPrincipal(KafkaPrincipal.USER_TYPE, principalName)
        override fun clientAddress(): InetAddress = InetAddress.getLoopbackAddress()
        override fun requestType() = 0
        override fun requestVersion() = 0
        override fun clientId() = "test"
        override fun correlationId() = 0
    }

    private fun decide(
        principal: String,
        type: ResourceType,
        resource: String,
        operation: AclOperation
    ): AuthorizationResult {
        val action = Action(
            operation,
            ResourcePattern(type, resource, PatternType.LITERAL),
            1, true, true
        )
        return authorizer.authorize(contextOf(principal), mutableListOf(action)).single()
    }

    private fun assertAllowed(result: AuthorizationResult, mensaje: String) =
        assertEquals(AuthorizationResult.ALLOWED, result, mensaje)

    private fun assertDenied(result: AuthorizationResult, mensaje: String) =
        assertEquals(AuthorizationResult.DENIED, result, mensaje)

    // ── Todos los grupos leen los eventos de todos ──

    @Test
    fun `un grupo puede leer el topico de otro grupo`() {
        assertAllowed(
            decide(movilidad, ResourceType.TOPIC, "$reclamos.ReclamoCreado", AclOperation.READ),
            "la integración entre equipos es el propósito del bus"
        )
    }

    @Test
    fun `un grupo puede describir cualquier topico de negocio`() {
        assertAllowed(
            decide(movilidad, ResourceType.TOPIC, "$reclamos.ReclamoCreado", AclOperation.DESCRIBE),
            "sin DESCRIBE el cliente no resuelve metadata y no puede consumir"
        )
    }

    @Test
    fun `los topicos de sistema quedan fuera del alcance`() {
        assertDenied(
            decide(movilidad, ResourceType.TOPIC, "sistema.dlq", AclOperation.READ),
            "la DLQ guarda payloads fallidos de cualquier equipo"
        )
    }

    // ── Nadie escribe directo al bus ──

    @Test
    fun `escribir en un topico propio esta prohibido`() {
        assertDenied(
            decide(movilidad, ResourceType.TOPIC, "$movilidad.BiciDevuelta", AclOperation.WRITE),
            "publicar es por el gateway, que valida el namespace y arma la metadata"
        )
    }

    @Test
    fun `escribir en el topico de otro grupo esta prohibido`() {
        assertDenied(
            decide(movilidad, ResourceType.TOPIC, "$reclamos.ReclamoCreado", AclOperation.WRITE),
            "si no, un grupo podría falsificar eventos a nombre de otro"
        )
    }

    @Test
    fun `crear y borrar topicos esta prohibido`() {
        assertDenied(
            decide(movilidad, ResourceType.TOPIC, "$movilidad.Nuevo", AclOperation.CREATE),
            "los tópicos los crea el gateway al registrar un event type"
        )
        assertDenied(
            decide(movilidad, ResourceType.TOPIC, "$movilidad.BiciDevuelta", AclOperation.DELETE),
            "borrar un tópico destruiría el historial de eventos"
        )
    }

    @Test
    fun `alterar la configuracion del cluster esta prohibido`() {
        assertDenied(
            decide(movilidad, ResourceType.CLUSTER, "kafka-cluster", AclOperation.ALTER),
            "ningún cliente administra el cluster"
        )
    }

    // ── Nadie consume a nombre de otro ──

    @Test
    fun `un grupo puede usar sus propios consumer groups`() {
        assertAllowed(
            decide(movilidad, ResourceType.GROUP, "$movilidad.reportes", AclOperation.READ),
            "el id empieza con su namespace"
        )
    }

    @Test
    fun `un grupo no puede usar el consumer group de otro`() {
        assertDenied(
            decide(movilidad, ResourceType.GROUP, "$reclamos.reportes", AclOperation.READ),
            "si no, se quedaría con los mensajes del otro equipo, que dejaría de recibirlos"
        )
    }

    @Test
    fun `un consumer group sin prefijo esta prohibido`() {
        assertDenied(
            decide(movilidad, ResourceType.GROUP, "consumidor-generico", AclOperation.READ),
            "un id genérico permitiría colisiones entre equipos"
        )
    }

    // ── Conexión ──

    @Test
    fun `describir el cluster esta permitido`() {
        assertAllowed(
            decide(movilidad, ResourceType.CLUSTER, "kafka-cluster", AclOperation.DESCRIBE),
            "un cliente necesita describir el cluster para conectarse"
        )
    }

    @Test
    fun `sobre el cluster solo se permite describir`() {
        assertDenied(
            decide(movilidad, ResourceType.CLUSTER, "kafka-cluster", AclOperation.READ),
            "describir alcanza para conectarse; cualquier otra cosa sobre el cluster no"
        )
    }

    @Test
    fun `los recursos que no reconoce se deniegan`() {
        assertDenied(
            decide(movilidad, ResourceType.TRANSACTIONAL_ID, "tx-1", AclOperation.DESCRIBE),
            "el default es denegar: lo que no está contemplado, no se permite"
        )
    }

    // ── Tráfico interno ──

    @Test
    fun `la plataforma en la red privada tiene acceso pleno`() {
        val anonimo = KafkaPrincipal.ANONYMOUS.name
        assertAllowed(
            decide(anonimo, ResourceType.TOPIC, "$movilidad.BiciDevuelta", AclOperation.WRITE),
            "el gateway publica por el listener interno"
        )
        assertAllowed(
            decide(anonimo, ResourceType.TOPIC, "sistema.dlq", AclOperation.WRITE),
            "la DLQ la escribe el gateway"
        )
    }

    // ── Configuración y ciclo de vida ──

    @Test
    fun `el prefijo de negocio se puede configurar`() {
        val otro = NamespaceAuthorizer().apply {
            configure(mutableMapOf("citypass.authorizer.business.prefix" to "org.otra."))
        }
        val action = Action(
            AclOperation.READ,
            ResourcePattern(ResourceType.TOPIC, "org.otra.Evento", PatternType.LITERAL),
            1, true, true
        )
        assertEquals(
            AuthorizationResult.ALLOWED,
            otro.authorize(contextOf(movilidad), mutableListOf(action)).single()
        )
        otro.close()
    }

    @Test
    fun `start completa el futuro de cada endpoint`() {
        val endpoint = Endpoint("EXTERNAL", SecurityProtocol.SASL_PLAINTEXT, "localhost", 9092)
        val info = object : AuthorizerServerInfo {
            override fun endpoints() = mutableListOf(endpoint)
            override fun interBrokerEndpoint() = endpoint
            override fun clusterResource() = org.apache.kafka.common.ClusterResource("test-cluster")
            override fun brokerId() = 1
            override fun earlyStartListeners() = mutableListOf<String>()
        }

        val futuros = authorizer.start(info)

        assertEquals(1, futuros.size)
        assertTrue(futuros.getValue(endpoint).toCompletableFuture().isDone)
    }

    @Test
    fun `varias acciones se resuelven de a una`() {
        val acciones = mutableListOf(
            Action(AclOperation.READ, ResourcePattern(ResourceType.TOPIC, "$movilidad.A", PatternType.LITERAL), 1, true, true),
            Action(AclOperation.WRITE, ResourcePattern(ResourceType.TOPIC, "$movilidad.A", PatternType.LITERAL), 1, true, true),
        )
        assertEquals(
            listOf(AuthorizationResult.ALLOWED, AuthorizationResult.DENIED),
            authorizer.authorize(contextOf(movilidad), acciones)
        )
    }

    @Test
    fun `la gestion de ACLs no tiene efecto`() {
        val ctx = contextOf(movilidad)
        assertTrue(authorizer.createAcls(ctx, mutableListOf()).isEmpty())
        assertTrue(authorizer.deleteAcls(ctx, mutableListOf()).isEmpty())
        assertTrue(authorizer.acls(AclBindingFilter.ANY).toList().isEmpty())
    }
}
