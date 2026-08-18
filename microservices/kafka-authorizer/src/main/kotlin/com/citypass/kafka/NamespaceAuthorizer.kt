package com.citypass.kafka

import org.apache.kafka.common.Endpoint
import org.apache.kafka.common.acl.AclBinding
import org.apache.kafka.common.acl.AclBindingFilter
import org.apache.kafka.common.acl.AclOperation
import org.apache.kafka.common.resource.ResourceType
import org.apache.kafka.common.security.auth.KafkaPrincipal
import org.apache.kafka.server.authorizer.AclCreateResult
import org.apache.kafka.server.authorizer.AclDeleteResult
import org.apache.kafka.server.authorizer.Action
import org.apache.kafka.server.authorizer.AuthorizableRequestContext
import org.apache.kafka.server.authorizer.Authorizer
import org.apache.kafka.server.authorizer.AuthorizerServerInfo
import org.apache.kafka.server.authorizer.AuthorizationResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Autoriza a partir del JWT que el cliente ya presentó, sin ACLs.
 *
 * El broker está configurado para tomar el principal del claim `namespace`, que
 * identifica al grupo. Toda la política se deriva de ahí, así que no hay ninguna lista
 * que mantener sincronizada con el servicio de identidad: si deja de emitir tokens
 * para un grupo, el acceso se corta solo. El servicio de identidad es la única fuente
 * de verdad, por construcción y no por convención.
 *
 * ── Política ────────────────────────────────────────────────────────────────
 *
 *   Tráfico interno (principal anónimo, listener sin autenticar):
 *       permitido — son los servicios de la plataforma en la red privada.
 *
 *   Cliente autenticado:
 *       Topic  · leer      · cualquier tópico bajo [businessPrefix]  → permitir
 *       Group  · leer      · id que empiece con su namespace         → permitir
 *       resto                                                        → denegar
 *
 * Los grupos leen los eventos de todos —la integración es el punto del bus— pero no
 * escriben: publicar es exclusivamente por el event-gateway, que valida el namespace,
 * calcula la metadata y sella el payload. Si pudieran producir directo, podrían
 * publicar en el tópico de otro equipo y falsificar el emisor de los eventos.
 *
 * La regla del consumer group impide que un grupo se meta en el de otro. Sin ella
 * bastaría con usar el `group.id` ajeno para que el broker le entregue a uno los
 * mensajes destinados al otro, que se quedaría esperando sin recibir nada ni ver error.
 */
private const val PREFIX_CONFIG = "citypass.authorizer.business.prefix"
private const val DEFAULT_BUSINESS_PREFIX = "com.citypass."

/** Operaciones que no modifican nada. */
private val READ_ONLY = setOf(AclOperation.READ, AclOperation.DESCRIBE)

class NamespaceAuthorizer : Authorizer {

    /** Prefijo de los tópicos de negocio. `sistema.*` queda afuera: ahí está la DLQ. */
    private var businessPrefix: String = DEFAULT_BUSINESS_PREFIX

    override fun configure(configs: MutableMap<String, *>) {
        businessPrefix = configs[PREFIX_CONFIG]?.toString() ?: DEFAULT_BUSINESS_PREFIX
    }

    override fun start(serverInfo: AuthorizerServerInfo): MutableMap<Endpoint, out CompletionStage<Void>> =
        serverInfo.endpoints().associateWith { CompletableFuture.completedFuture<Void>(null) }.toMutableMap()

    override fun authorize(
        context: AuthorizableRequestContext,
        actions: MutableList<Action>
    ): MutableList<AuthorizationResult> =
        actions.map { decide(context.principal(), it) }.toMutableList()

    private fun decide(principal: KafkaPrincipal, action: Action): AuthorizationResult {
        // El listener interno no autentica, así que su principal es anónimo: es la
        // propia plataforma hablando por la red privada.
        if (principal.name == KafkaPrincipal.ANONYMOUS.name) return AuthorizationResult.ALLOWED

        if (action.operation() !in READ_ONLY) return AuthorizationResult.DENIED

        val resource = action.resourcePattern().name()
        return when (action.resourcePattern().resourceType()) {
            ResourceType.TOPIC ->
                allowIf(resource.startsWith(businessPrefix))

            // El id del consumer group debe empezar con el namespace del grupo.
            ResourceType.GROUP ->
                allowIf(resource.startsWith(principal.name))

            // Los clientes necesitan describir el cluster para conectarse.
            ResourceType.CLUSTER ->
                allowIf(action.operation() == AclOperation.DESCRIBE)

            else -> AuthorizationResult.DENIED
        }
    }

    private fun allowIf(condition: Boolean) =
        if (condition) AuthorizationResult.ALLOWED else AuthorizationResult.DENIED

    // ── Gestión de ACLs: no aplica ──
    // Este autorizador no guarda estado. Devolver listas vacías deja las APIs de
    // administración sin efecto en vez de romper a los clientes que las consulten.

    override fun createAcls(
        context: AuthorizableRequestContext,
        bindings: MutableList<AclBinding>
    ): MutableList<out CompletionStage<AclCreateResult>> = mutableListOf()

    override fun deleteAcls(
        context: AuthorizableRequestContext,
        filters: MutableList<AclBindingFilter>
    ): MutableList<out CompletionStage<AclDeleteResult>> = mutableListOf()

    override fun acls(filter: AclBindingFilter): MutableIterable<AclBinding> = mutableListOf()

    override fun close() = Unit
}
