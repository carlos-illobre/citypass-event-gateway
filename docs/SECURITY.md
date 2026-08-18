# Seguridad

Cómo está protegido el bus de eventos, regla por regla, y dónde está implementada cada una.

El principio que ordena todo el diseño: **si algo se puede configurar mal, tiene que fallar
cerrado**. Los valores por omisión son siempre los restrictivos, y aflojarlos exige un acto
explícito.

---

## Contenido

1. [Modelo de amenazas](#1-modelo-de-amenazas)
2. [Identidad y autenticación](#2-identidad-y-autenticación)
3. [Autorización](#3-autorización)
4. [Integridad de los eventos](#4-integridad-de-los-eventos)
5. [Aislamiento entre grupos](#5-aislamiento-entre-grupos)
6. [Superficie expuesta](#6-superficie-expuesta)
7. [Protección contra abuso](#7-protección-contra-abuso)
8. [Secretos](#8-secretos)
9. [Lo que falta](#9-lo-que-falta)

---

## 1. Modelo de amenazas

Contra quién se protege el sistema, en orden de probabilidad real:

| Amenaza | Qué tan probable | Cómo se mitiga |
|---|---|---|
| **Un grupo se equivoca** y publica en el tópico de otro, o suscribe una URL que no corresponde | Alta | Autorización por namespace, validación de destinos |
| **Un grupo lee datos de otro** sin querer, por un endpoint mal acotado | Alta | Filtrado por dueño en suscripciones, DLQ y consulta de eventos |
| **Un evento se atribuye a quien no lo publicó** | Media | La metadata la calcula el gateway, no el productor |
| **Alguien de afuera** llega al bus sin credenciales | Media | Todo exige JWT; en producción sólo el proxy está expuesto |
| **Un atacante intercepta un token** en la red | Media en producción | TLS en las dos superficies |
| **Un grupo tira abajo el servicio** con un loop | Media | Límite de tamaño y rate limiting por namespace |

La frase que guía el diseño es que **se confía en la honestidad de los equipos, pero no en
su experiencia**. Casi todas las defensas están pensadas para el error, no para el ataque —
y como efecto secundario cubren también al ataque.

---

## 2. Identidad y autenticación

### Una sola fuente de verdad

Hay **un solo emisor de identidad** para toda la plataforma. El mismo token sirve para la
API REST y para conectarse a Kafka, y los dos validan contra el mismo JWKS.

La consecuencia práctica: dar de baja a un cliente en el servicio de identidad lo deja
afuera del bus **y** de la API a la vez, sin tocar ninguna lista en ningún otro lado.

Hoy ese emisor es `auth-simulator`, un mock. Lo reemplaza el Grupo 2; cuando llegue, cambia
de dónde salen los tokens, no cómo se validan.

### Qué se valida

`SecurityConfig` construye el decodificador contra el JWKS del servicio de identidad y le
suma la validación de audiencia:

```kotlin
NimbusJwtDecoder.withJwkSetUri("$authServiceUrl/.well-known/jwks.json").build().apply {
    setJwtValidator(DelegatingOAuth2TokenValidator(
        JwtValidators.createDefault(),
        JwtClaimValidator<List<String>>(JwtClaimNames.AUD) { it != null && audience in it }))
}
```

Se comprueba, en este orden:

| Qué | Por qué |
|---|---|
| **Firma RS256** contra la clave pública del JWKS | Un token fabricado no pasa |
| **Expiración** | Un token filtrado deja de servir |
| **Audiencia** (`aud` contiene `citypass`) | Un token emitido para otro sistema por el mismo emisor no sirve acá |

La clave pública se obtiene del JWKS y no se configura a mano: rotar la clave del emisor no
requiere tocar el gateway.

### Claims que el token tiene que traer

| Claim | Para qué se usa |
|---|---|
| `sub` | Queda como `metadata.source` del evento: quién publicó |
| `namespace` | Decide en qué tópicos puede publicar y leer |
| `aud` | Tiene que contener `citypass` |
| `jti` | Queda como `metadata.tokenId`: permite trazar hasta la emisión concreta |
| `exp` / `iat` | Vigencia |

---

## 3. Autorización

### En la API REST

Sólo dos rutas son públicas, y las dos por un motivo concreto:

```kotlin
.requestMatchers("/health").permitAll()
.requestMatchers(HttpMethod.GET, "/api/v1/schemas/**").permitAll()
.anyRequest().authenticated()
```

- **`/health`** lo consulta el orquestador, que no tiene con qué autenticarse, y no expone
  ningún dato.
- **`GET /api/v1/schemas/**`** lo usan los deserializadores estándar de Avro, que lo llaman
  solos al leer un evento y cuyo soporte de Bearer varía según la librería. Es de sólo
  lectura y devuelve contratos, no datos de negocio.

Todo lo demás exige token. Publicar exige además que el tópico sea del namespace propio:

```kotlin
fun isAllowed(jwt: Jwt?, topic: String): Boolean {
    if (jwt == null) return false
    val namespace = jwt.claims["namespace"] as? String ?: return false
    return topic.startsWith("$namespace.")
}
```

**No hay usuario administrador ni namespace comodín.** Es deliberado: un cliente con acceso
a todos los tópicos es una llave maestra que, filtrada, compromete la plataforma entera. Un
`*` en el namespace no da acceso a todo — no coincide con ningún prefijo, así que no da
acceso a nada.

### En Kafka

El broker corre un autorizador propio, `NamespaceAuthorizer`, que **deriva la política del
token en cada conexión**:

```kotlin
if (principal.name == KafkaPrincipal.ANONYMOUS.name) return ALLOWED   // listener interno
if (action.operation() !in READ_ONLY) return DENIED
return when (action.resourcePattern().resourceType()) {
    ResourceType.TOPIC   -> allowIf(resource.startsWith(businessPrefix))
    ResourceType.GROUP   -> allowIf(resource.startsWith(principal.name))
    ResourceType.CLUSTER -> allowIf(action.operation() == AclOperation.DESCRIBE)
    else -> DENIED
}
```

Tres reglas:

1. **Sólo lectura.** Un cliente externo no puede escribir en ningún tópico. Publicar es
   siempre por la API, que es lo que garantiza la metadata.
2. **Sólo consumer groups con su prefijo.** Sin esto, un grupo podría usar el `group.id` de
   otro y robarle los mensajes: Kafka reparte las particiones entre los miembros del grupo,
   así que el intruso recibiría eventos que el legítimo dejaría de ver. Con una partición,
   la víctima se queda sin recibir nada.
3. **Del cluster, sólo describir.** Lo mínimo para que un cliente descubra particiones.

**No hay ACLs.** Ese es el punto: las ACLs de Kafka son estado guardado en el cluster, y
mantenerlas exigiría una lista de grupos sincronizada a mano con el servicio de identidad —
dos fuentes de verdad que se desincronizan solas. Al derivar la política del token, si el
emisor deja de emitir tokens para un grupo, el acceso se corta solo.

El principal de Kafka es el **namespace** y no el `sub`, porque un consumer group es de la
aplicación: dos instancias del mismo grupo con credenciales distintas tienen que poder
compartir el `group.id`.

**Lo que la regla 2 no acota: cuántos grupos.** Exige un prefijo, no una lista, así que el
resto del nombre lo elige el cliente y puede usar uno distinto cada vez. Eso importa porque
los offsets van a `__consumer_offsets`, que es **compactado**: la compactación conserva el
último registro por clave y la clave incluye el `group.id`, de modo que cada nombre nuevo es
una clave que nunca se colapsa. `KAFKA_RETENTION_BYTES` no lo toca —acota tópicos por
tamaño, no compactados— y Kafka no tiene ningún límite de cantidad de grupos.

Se acota con dos opciones del broker, porque no hay forma de hacerlo desde el authorizer sin
guardar estado:

| Opción | Qué hace |
|---|---|
| `KAFKA_OFFSETS_RETENTION_MINUTES` | Vence el offset de un grupo inactivo, así que en régimen el tópico guarda los grupos vistos en la ventana y no todos los de la historia |
| `KAFKA_OFFSETS_SEGMENT_BYTES` | El segmento activo nunca se compacta; con el default de 100 MB la limpieza recién empieza ahí |

El precio de la primera es real: un consumidor apagado más que la ventana pierde su posición
y vuelve según su `auto.offset.reset`.

---

## 4. Integridad de los eventos

Es la garantía más importante del sistema y no se apoya en ninguna validación.

Un evento tiene dos records separados: `data`, que escribe el productor, y `metadata`, que
calcula el gateway. **No hay forma de escribir metadata desde un request** — no es que se
valide y se rechace: es que el campo no existe en el body.

```kotlin
val envelope = mapOf<String, Any>(
    "metadata" to mapOf(
        "source"      to (jwt.subject ?: "unknown"),   // del token, no del body
        "tokenId"     to (jwt.id ?: "unknown"),
        "payloadHash" to avroService.payloadHash(data, dataField.schema()),
        ...
    ),
    "data" to data
)
```

Consecuencias:

- **`source` es de fiar.** El grupo 3 no puede publicar un evento que diga `source: grupo7`.
- **No hay nombres reservados.** Un campo de negocio puede llamarse `source` o `eventId` sin
  pisar nada, porque vive en otro record.
- **`payloadHash` permite verificar** que el payload no se alteró, y deduplicar por
  contenido.

---

## 5. Aislamiento entre grupos

Los tópicos de un namespace pertenecen al grupo, pero varios endpoints devolverían datos de
otros si no se filtraran. Cada uno tiene su regla:

| Endpoint | Regla | Por qué |
|---|---|---|
| `GET /api/v1/subscriptions` | Sólo las del namespace del token | Listar las ajenas expondría las URLs internas de otros equipos y los ids con los que darlas de baja |
| `DELETE /api/v1/subscriptions/{id}` | Sólo las propias, y una ajena responde **404** y no 403 | Un 403 confirmaría que ese id existe |
| `GET /api/v1/dead-letters` | Sólo las entradas cuyo `owner` coincide | Una entrada lleva el payload del evento y el mensaje de error |
| `GET /api/v1/events` | Sólo tópicos del namespace, y dentro sólo los del `sub` | Aislamiento entre grupos y entre usuarios del mismo grupo |

El `owner` de una entrada de la DLQ merece una aclaración: para un fallo de deserialización
es el dueño del **tópico**, pero para un fallo de entrega de webhook es el dueño de la
**suscripción**, que no es el mismo. Quien se suscribe a los eventos de otro grupo necesita
ver por qué le fallan sus entregas, y el dueño del tópico no tiene por qué ver la URL
interna del suscriptor.

---

## 6. Superficie expuesta

### En producción, un solo servicio escucha

Todos los contenedores publican sus puertos sobre `127.0.0.1` (`PUBLISH_ADDR`), así que
desde internet **no existen**. El único que escucha en todas las interfaces es el
reverse-proxy, que abre 80, 443 y 9092.

Eso cierra un agujero que existía antes: el Schema Registry (8081) y kafka-ui (8090)
estaban expuestos y **ninguno de los dos tiene autenticación propia**. Cualquiera en
internet podía borrar subjects del registry o administrar el cluster.

Para llegar a ellos desde la VM se usa un túnel SSH, sin abrir ningún puerto:

```bash
ssh -L 8081:127.0.0.1:8081 -L 8090:127.0.0.1:8090 -L 9090:127.0.0.1:9090 usuario@dominio
```

### TLS

El proxy termina TLS en las dos superficies: HTTPS para la API y la UI, y un listener
`stream` que envuelve en TLS el puerto de Kafka. Sin esto el JWT viaja en claro, y como todo
el modelo de seguridad cuelga de ese token, capturarlo alcanza para publicar y consumir en
nombre de un grupo.

El certificado es de Let's Encrypt, o sea de una CA pública. Esa decisión es de seguridad y
no de comodidad: con un certificado propio, cada grupo tendría que instalar un truststore en
su cliente de Kafka, y el que no lo lograra terminaría desactivando la verificación — que es
peor que no tener TLS, porque parece seguro.

### kafka-ui

Utiliza excepcionalmente una credencial del sistema fuera del servicio de autenticacion, y es una excepción
justificada: kafka-ui es una herramienta de administración y el emisor sólo soporta
`client_credentials`, así que un navegador no tiene cómo autenticarse contra él.

La autenticación va **adentro de kafka-ui** (`AUTH_TYPE=LOGIN_FORM`) y no en el proxy. Es a
propósito: si `KAFKA_UI_PUBLISH_ADDR` quedara mal configurado, se llegaría al 8090 sin pasar
por nginx y una autenticación puesta en el proxy sería decorativa.

El compose **no arranca** si falta la credencial:

```yaml
SPRING_SECURITY_USER_NAME: ${KAFKA_UI_USER:?falta KAFKA_UI_USER en el .env}
```

Es la única variable obligatoria del proyecto: para una contraseña no existe un default
seguro.

### Grafana

Mismo criterio que kafka-ui y por el mismo motivo: es una herramienta de operación y el
emisor de identidad sólo hace `client_credentials`, así que un navegador no puede
autenticarse contra él. Lleva su propio login, el compose **no arranca** si faltan las
credenciales, y el registro de usuarios está deshabilitado (`GF_USERS_ALLOW_SIGN_UP=false`).

Prometheus **no tiene autenticación** —no la trae de fábrica— y por eso no se publica nunca
hacia afuera: en producción queda en loopback y se llega por túnel SSH. Sus datos son
métricas operativas, no de negocio, pero revelan volumen de tráfico por grupo.

### Puertos que nunca se publican

- **9093** (controller de KRaft) — es el plano de control del cluster. Exponerlo sería una
  puerta lateral por encima de toda la autorización del listener externo.
- **29092** (listener interno) — sin autenticar, sólo alcanzable dentro de la red de Docker.
- **9090** (métricas del gateway), **9091** (Prometheus) y **3000** (Grafana) — en
  producción quedan en loopback y no los rutea el proxy.

### Creación de tópicos

`auto.create.topics.enable` está en `false`, y además los consumidores del gateway van con
`allow.auto.create.topics=false`. Un tópico sólo nace de una creación explícita: el gateway
al registrar un event type. Sin esto, un error de tipeo en un nombre crea un tópico nuevo en
silencio en vez de fallar.

---

## 7. Protección contra abuso

### SSRF por webhooks

El gateway hace un `POST` a la `callbackUrl` de cada suscripción. Sin validar, cualquier
grupo autenticado podría hacer que el gateway golpee direcciones que sólo son alcanzables
desde adentro: el endpoint de metadata del proveedor cloud (`169.254.169.254`), el Schema
Registry, el broker o el propio gateway.

`CallbackUrlValidator` resuelve el host y rechaza loopback, link-local, rangos privados,
wildcard, multicast, unique-local IPv6 y CGNAT.

Lo importante es **cuándo** se valida: en **cada intento de entrega**, no sólo al registrar
la suscripción. Validar sólo al registrar no sirve contra DNS rebinding — el dueño de un
dominio puede devolver una IP pública cuando se registra y una privada cuando se entrega.

Queda una limitación conocida: entre esa resolución y la que hace el cliente HTTP al
conectar hay una ventana en la que un registro con TTL 0 podría cambiar. Cerrarla exige un
cliente HTTP con resolver propio, que el del JDK no expone.

En desarrollo la validación se desactiva con `SPRING_PROFILES_ACTIVE=development`, porque
los consumidores del compose son contenedores con IP privada.

### Límites

| Límite | Valor | Qué evita |
|---|---|---|
| Tamaño del body | 256 KB (1 MB en nginx) | Un evento de cientos de megas se guarda, se replica y se entrega a cada suscriptor |
| Peticiones por minuto | 600 por namespace | Que el loop de un grupo deje sin servicio a los otros siete |
| Conexiones por IP al 9092 | `NGINX_KAFKA_CONN_LIMIT` | Agotar `worker_connections` de nginx y con eso tumbar también la API y la UI |

El último es el único que actúa **sin credenciales de por medio**: la conexión TLS al 9092 se
acepta antes de negociar SASL, así que hasta ese punto el atacante no se identificó con nada.
Vive en el bloque `stream` de nginx y no en el `http`, porque son dos contextos distintos y
`limit_conn` de uno no alcanza al otro — el límite de peticiones y el de conexiones de la
tabla de arriba sólo cubren el 443. Junto al techo van `proxy_connect_timeout` y
`proxy_timeout`: sin ellos una conexión que abre y no habla ocupa un lugar para siempre, que
es la misma denegación de servicio por otro camino.

La cuota es **por namespace y no global**: si fuera global, el error de un grupo afectaría a
todos, que es exactamente lo que se busca evitar. Devuelve `429` con `Retry-After`, porque
sin esa cabecera el cliente reintenta enseguida y agrava el problema.

### CORS

Los orígenes permitidos son explícitos y **nunca un asterisco**:

```
GATEWAY_CORS_ORIGIN=https://citypass.tudominio.com
```

Con CORS abierto, cualquier página que visite un usuario logueado podría hacer llamadas al
gateway con su sesión.

---

## 8. Secretos

| Qué | Dónde vive | Versionado |
|---|---|---|
| Credenciales de los grupos | Código del `auth-simulator` | Sí, **es un mock** que se reemplaza |
| Contraseña de kafka-ui | `.env` de la máquina | No — `.env` está en `.gitignore` |
| Certificado TLS | Volumen `certbot-conf` | No |
| Clave de firma de los JWT | En memoria del emisor | No |

`.env.example` **sí** se versiona, porque es una plantilla. Por eso
lleva `KAFKA_UI_USER=CAMBIAR`: la contraseña real se escribe sólo en el `.env` de la VM.

Un script de verificación comprueba que los dos archivos declaren exactamente las mismas
variables, para que ninguna quede definida en uno solo:

```bash
./tests/itest.sh
```

---

## 9. Lo que falta

Honestidad sobre el estado actual:

**Bloqueante:** el servicio de identidad es un mock. Credenciales fijas en el código y clave
de firma que se regenera en cada arranque, o sea que cada reinicio invalida todos los tokens
emitidos. El gateway ya está preparado para un emisor real; la plataforma no lo está hasta
que exista.

**No bloqueantes, pero conviene saberlos:**

- El cifrado termina en el proxy; el último salto hasta el broker viaja en claro por el
  bridge de Docker, dentro de la misma VM. Alcanza mientras todo corra en una máquina.
- El rate limiting es en memoria, o sea por instancia. Con una sola instancia es exacto.
- No hay auditoría persistente de accesos: los logs son la única traza y no se agregan en
  ningún lado.
- Queda la ventana de DNS rebinding descrita arriba.

---

## Referencias

- [AUTH.md](AUTH.md) — el contrato que tiene que cumplir el servicio de identidad
- [ARCHITECTURE.md](ARCHITECTURE.md) — por qué publicar por HTTP y consumir por Kafka
- [ADR-011](adr/ADR-011-autorizacion-derivada-del-token.md) — autorizador propio en vez de ACLs
- [ADR-012](adr/ADR-012-envelope-metadata-data.md) — separación estructural de metadata y data
- [DEPLOYMENT.md](DEPLOYMENT.md) — TLS, proxy y puertos en producción
