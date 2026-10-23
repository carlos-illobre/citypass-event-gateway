# Servicio de identidad — contrato y cómo está enchufado

Los tokens del bus los emite **login-federado**, el servicio de identidad del **Grupo 2**,
publicado en `https://api.citypass-login.pp.ua` (repo `CityPass_Login_Backend`). Este
documento describe qué espera el bus de esos tokens, quién valida qué, y cómo está
configurado de nuestro lado.

Hasta octubre de 2026 el bus traía su propio emisor de prueba (`auth-simulator`). Se retiró
cuando el de Grupo 2 quedó en producción: el contrato es el mismo, así que el cambio fue de
configuración y no de código ([ADR-024](adr/ADR-024-identidad-delegada-en-login-federado.md)).

---

## Contenido

1. [Por qué importa tanto](#1-por-qué-importa-tanto)
2. [Los endpoints que usamos](#2-los-endpoints-que-usamos)
3. [El token](#3-el-token)
4. [Quién valida qué](#4-quién-valida-qué)
5. [Restricciones que no son negociables](#5-restricciones-que-no-son-negociables)
6. [Diferencias con lo que esperábamos, y cómo las absorbemos](#6-diferencias-con-lo-que-esperábamos-y-cómo-las-absorbemos)
7. [Cómo verificar que cumple](#7-cómo-verificar-que-cumple)
8. [Cómo está enchufado](#8-cómo-está-enchufado)

---

## 1. Por qué importa tanto

En esta plataforma **la identidad es la única fuente de verdad sobre quién puede hacer qué**.
No hay listas de permisos en ningún otro lado: ni ACLs en Kafka, ni tabla de grupos en el
EDA, ni configuración que haya que mantener sincronizada.

Eso tiene una consecuencia buena y una exigente:

- **Buena:** dar de baja un cliente en login-federado lo deja afuera del bus *y* de la API a
  la vez, sin que nadie tenga que borrarlo de ningún otro lugar.
- **Exigente:** si se emite un token con el `namespace` equivocado, ese cliente publica y
  consume como otro grupo. No hay una segunda barrera que lo detenga.

Tres componentes validan los tokens, todos contra el mismo JWKS: el **event-gateway** (la API
REST), el **webhook-dispatcher** (suscripciones y cola de fallidos) y el **broker de Kafka**
(para los consumidores que se conectan directo). Ninguno tiene secretos: sólo la clave
pública.

---

## 2. Los endpoints que usamos

### `POST /oauth/token`

Flujo `client_credentials` de OAuth 2.0 ([RFC 6749 §4.4](https://www.rfc-editor.org/rfc/rfc6749#section-4.4)).
Lo usan los backends de cada grupo, los consumidores de Kafka y la consola (vía `/auth/`).

```http
POST /oauth/token
Authorization: Basic base64(client_id:client_secret)
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
```

Acepta las credenciales en el header Basic y también en el cuerpo (`client_id`,
`client_secret`). **El Basic no es opcional para nosotros**: el cliente de Kafka lo usa
siempre cuando un consumidor configura `sasl.oauthbearer.token.endpoint.url`.

Respuesta `200`:

```json
{ "access_token": "eyJhbGciOiJSUzI1NiIs...", "token_type": "Bearer", "expires_in": 900 }
```

Errores con la forma del RFC (`{ "error", "error_description" }`): `400 unsupported_grant_type`,
`400 invalid_request`, `401 invalid_client`.

### `GET /.well-known/jwks.json`

Público. Lo bajan el gateway, el dispatcher y el broker.

```json
{ "keys": [ { "kty": "RSA", "use": "sig", "alg": "RS256", "e": "AQAB", "kid": "QIPyXFdRJYL87-BZCjUYwBPUuTHBtZFJeiD8cqcysto", "n": "…" } ] }
```

El `kid` es la huella RFC 7638 de la clave: si la clave no cambia, el `kid` tampoco.

### `GET /actuator/health`

Público. Es el que consulta la consola (como `/auth/health`, que los proxies traducen). Su
`/health` pide autenticación.

---

## 3. El token

Firmado con **RS256**; el header lleva el `kid`. Claims de un token de servicio:

| Claim | Valor | Quién lo usa y para qué |
|---|---|---|
| `sub` | El `client_id` | El gateway lo estampa en `metadata.source` de cada evento. Es la traza de quién publicó |
| `namespace` | El identificador del grupo | **El más importante.** Delimita en qué tópicos puede publicar (`<namespace>.*`) y es la identidad con la que Kafka autoriza el consumo |
| `aud` | **Lista** que contiene `citypass` | La verifican gateway, dispatcher y broker. Se comprueba que la nuestra esté **adentro**, no que sea igual |
| `iss` | `https://idp.citypass.local` | Gateway y dispatcher la comparan **literalmente** contra `TOKEN_ISSUER`. No es una URL a la que alguien se conecte: sólo se compara como texto |
| `token_use` | `service` | Gateway y dispatcher sólo aceptan credenciales de servicio. Ver abajo |
| `ver` | `1` | Versión del contrato. Se rechaza lo que no se entiende en vez de interpretarlo con reglas de otra versión |
| `jti` | Único por emisión | El gateway lo estampa en `metadata.tokenId` |
| `iat`, `exp` | Emisión y vencimiento (15 min) | `exp` lo verifican los tres validadores |

Ejemplo de payload:

```json
{
  "iss": "https://idp.citypass.local",
  "sub": "grupo1",
  "aud": ["citypass"],
  "namespace": "com.citypass.bus",
  "token_use": "service",
  "ver": 1,
  "jti": "af2480cc-487f-474f-ac06-f396ad3f403d",
  "iat": 1786547143,
  "exp": 1786548043
}
```

### Por qué se exige `token_use: service`

Publicar en el bus es cosa de un **backend**, no de una persona. Cuando alguien hace algo
que dispara un evento, el servicio de ese grupo autoriza a la persona con el token humano
de su propia API, y publica el evento con **su propio token de servicio**. La identidad de
la persona viaja como **dato del evento**, en un campo `actorSub` del record `data`
([CONTRACTS.md](CONTRACTS.md#el-actor-humano-detrás-de-un-evento)).

login-federado también emite tokens humanos (`/auth/login`, contra LDAP), con
`token_use: human`, `groups` y `module` pero **sin `namespace`**. El bus los rechaza con 401
a propósito: nunca hay que reenviarle el token de una persona.

### Sobre `sub` y `namespace`

Están separados a propósito. `namespace` **identifica a la aplicación**, y por eso Kafka lo
usa como principal: dos instancias del mismo grupo tienen que poder compartir el mismo
`group.id`. `sub` **identifica a quien pidió el token**. Lo que **no** puede pasar es que
`namespace` varíe entre las credenciales de un mismo grupo.

### Los clientes de servicio

Los registra el Grupo 2 en su `application.yml` (`citypass.clients`, `type: service`). No son
entradas de LDAP y no hay forma de crearlos desde nuestro lado.

| Cliente | Namespace |
|---|---|
| `grupo1` | `com.citypass.bus` — el de este equipo |
| `grupo2` | `com.citypass.auth` |
| `grupo3` | `com.citypass.movilidad` |
| `grupo4` | `com.citypass.reclamos` |
| `grupo5` | `com.citypass.emergencias` |
| `grupo6` | `com.citypass.turismo` |
| `grupo7` | `com.citypass.transporte` |
| `grupo8` | `com.citypass.analitica` |

El Grupo 2 mantiene además `group1` (`com.citypass.bus`) y `group5`
(`com.citypass.analitica`) como clientes heredados de compatibilidad temporal. No hay que
usarlos: los van a retirar. El secreto de cada cliente lo entrega el Grupo 2 a su grupo.

Nuestro código no tiene ninguna lista de namespaces ni de clientes: todo sale del token, así
que sumar un cliente del lado del Grupo 2 alcanza para que ese grupo opere.

Falta uno en la tabla a propósito: **`com.citypass.gateway` no es de ningún cliente**. Es el
namespace del gateway mismo, donde vive `EsquemaCambiado`. Todos pueden leerlo y ninguno
escribirlo, porque la autorización de escritura exige que el tópico empiece con el namespace
del token.

---

## 4. Quién valida qué

### event-gateway y webhook-dispatcher

Los dos construyen su validador contra `AUTH_SERVICE_URL/.well-known/jwks.json`
(`config/SecurityConfig.kt` de cada uno) y comprueban:

1. **Firma RS256** con la clave del `kid`. Ante un `kid` desconocido vuelven a bajar el JWKS.
2. **`exp`** — token vencido, rechazado.
3. **`aud` contiene `TOKEN_AUDIENCE`** (`citypass`).
4. **`token_use` es `service`.**
5. **`ver` es `1`** (si viene).
6. **`iss` es exactamente `TOKEN_ISSUER`.**

Después leen `sub`, `namespace` y `jti`. Un token sin `namespace` recibe un `400` explícito
en vez de un `401`, porque el problema no es la autenticación sino el contenido.

### El broker de Kafka

SASL `OAUTHBEARER` contra el mismo JWKS, con:

```yaml
KAFKA_SASL_OAUTHBEARER_SUB_CLAIM_NAME: 'namespace'
```

**El broker toma `namespace` como principal**, no `sub`. Un token sin `namespace` no puede
ni conectarse. También verifica `aud` y `exp`. Relee el JWKS cada `KAFKA_JWKS_REFRESH_MS`
(por intervalo, no ante un `kid` desconocido).

---

## 5. Restricciones que no son negociables

**Ningún cliente con permisos sobre todo.** No hay namespace comodín ni usuario
administrador: una credencial así sería una llave maestra sobre el bus entero.

**El formato del `namespace` no cambia.** Es el prefijo literal de los tópicos. `movilidad`
en vez de `com.citypass.movilidad` deja al grupo sin acceso a sus propios tópicos.

**Sólo RS256.** Gateway, dispatcher y broker tienen sólo la clave pública, que es lo que
hace que puedan validar sin poder emitir.

**`aud` incluye `citypass`.**

---

## 6. Diferencias con lo que esperábamos, y cómo las absorbemos

El criterio es pedirle al Grupo 2 el mínimo indispensable: lo que se puede resolver de
nuestro lado, se resuelve acá.

| Diferencia | Cómo la absorbemos |
|---|---|
| `iss` es `https://idp.citypass.local`, un nombre que no resuelve | Da igual: sólo se compara como texto. `TOKEN_ISSUER` tiene ese valor |
| `/health` pide token | Los proxies traducen `/auth/health` a `/actuator/health`, que es público |

Si el Grupo 2 cambia el `iss`, todos los pedidos dan 401 hasta actualizar `TOKEN_ISSUER` en
el `.env`. Si rota la clave, no hay que hacer nada: el `kid` nuevo dispara la relectura del
JWKS (en el broker, dentro de `KAFKA_JWKS_REFRESH_MS`).

---

## 7. Cómo verificar que cumple

Contra producción del Grupo 2, con un secreto real (por ejemplo el de `grupo1`) exportado en
la sesión. **Nunca lo escribas en un archivo versionado.**

```bash
AUTH=https://api.citypass-login.pp.ua
# export CLIENT_SECRET_GRUPO1=...   (te lo pasa el Grupo 2)

# 1. Emite un token con Basic — la forma que usa el cliente de Kafka
curl -s -X POST $AUTH/oauth/token -u grupo1:$CLIENT_SECRET_GRUPO1 \
  -d grant_type=client_credentials | jq
# Esperado: access_token, token_type "Bearer", expires_in numérico

# 2. Rechaza credenciales incorrectas con 401
curl -s -o /dev/null -w '%{http_code}\n' -X POST $AUTH/oauth/token \
  -u grupo1:incorrecta -d grant_type=client_credentials
# Esperado: 401

# 3. El JWKS es público
curl -s $AUTH/.well-known/jwks.json | jq '.keys[] | {kty, kid}'
```

```bash
# 4. Los claims del token, y que el kid del header esté en el JWKS
TOKEN=$(curl -s -X POST $AUTH/oauth/token -u grupo1:$CLIENT_SECRET_GRUPO1 \
  -d grant_type=client_credentials | jq -r .access_token)

# Un JWT usa base64url, que no es el base64 estándar: `base64 -d` falla con `-`, `_` y el
# relleno ausente. Este decodificador sí lo maneja.
jwt() { python3 -c "import sys,base64,json;s=sys.stdin.read().strip();print(json.dumps(json.loads(base64.urlsafe_b64decode(s+'='*(-len(s)%4))),indent=2))"; }

echo $TOKEN | cut -d. -f1 | jwt     # header:  alg RS256 + kid
echo $TOKEN | cut -d. -f2 | jwt     # payload: iss, aud, namespace, token_use, ver
# El iss tiene que ser exactamente el TOKEN_ISSUER del .env
```

```bash
# 5. El bus lo acepta (con el stack levantado)
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/event-types
# Esperado: 200. Un 401 casi siempre es `iss` distinto de TOKEN_ISSUER.
```

La prueba definitiva es **conectar un consumidor de Kafka** con
`sasl.oauthbearer.token.endpoint.url=https://api.citypass-login.pp.ua/oauth/token`: ejercita
Basic, `expires_in`, JWKS y `namespace` como principal de una sola vez. Hay ejemplos en el
[README](../README.md#6-consumir-eventos-desde-kafka).

Los tests de integración (`tests/integration/`) piden el token igual: exportá
`CLIENT_SECRET_GRUPO1` (o el del cliente que pongas en `CLIENTE_PRUEBA`, con su `NS_PRUEBA`).

---

## 8. Cómo está enchufado

Dos variables en el `.env`, sin código:

```bash
AUTH_SERVICE_URL=https://api.citypass-login.pp.ua   # JWKS para gateway, dispatcher y broker; destino de /auth/
TOKEN_ISSUER=https://idp.citypass.local             # el iss que emite hoy login-federado
```

`docker-compose.yml` la reparte: `AUTH_SERVICE_URL` al gateway y al dispatcher,
`${AUTH_SERVICE_URL}/.well-known/jwks.json` al broker, y como `AUTH_UPSTREAM` al nginx de la
consola. El `reverse-proxy` de producción también la recibe y sigue publicando
`https://<dominio>/auth/…` hacia login-federado, así que los grupos que ya usaban esa URL no
cambian nada.

**Ojo con dos cosas:**

- Gateway, dispatcher, broker y los dos nginx bajan el JWKS o reenvían **desde adentro de
  Docker hacia internet**. La instancia necesita salida HTTPS a `api.citypass-login.pp.ua`.
  Los nginx verifican el certificado contra las CA del sistema, porque por ahí viajan los
  `client_secret`.
- No hay `depends_on` posible: si login-federado está caído al arrancar, el bus levanta
  igual, pero nadie consigue tokens nuevos hasta que vuelva. Los tokens vigentes siguen
  valiendo hasta su `exp`.

---

## Referencias

- [IDENTIDAD-CAMBIOS-REQUERIDOS.md](IDENTIDAD-CAMBIOS-REQUERIDOS.md) — lo único que le pedimos al Grupo 2
- [externo/GRUPO-2-AUTENTICACION.md](externo/GRUPO-2-AUTENTICACION.md) — la especificación del Grupo 2
- [SECURITY.md](SECURITY.md) — qué se apoya en estos tokens
- [ADR-011](adr/ADR-011-autorizacion-derivada-del-token.md) — por qué no hay ACLs
- [ADR-024](adr/ADR-024-identidad-delegada-en-login-federado.md) — por qué se retiró el simulador
- [README §3](../README.md#3-autenticación) — cómo usan los tokens los demás grupos
