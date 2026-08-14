# Servicio de identidad — qué hay que implementar

Este documento es para el **Grupo 2**. Describe exactamente qué tiene que hacer el servicio
de identidad real para reemplazar al `auth-simulator` sin que haya que tocar nada del bus.

El simulador que está en el repo (`auth-simulator/src/index.js`) es una implementación
funcional y mínima del contrato. Sirve como referencia ejecutable: si tu servicio hace lo
mismo, entra sin cambios.

---

## Contenido

1. [Por qué importa tanto](#1-por-qué-importa-tanto)
2. [Los dos endpoints](#2-los-dos-endpoints)
3. [El token](#3-el-token)
4. [Quién valida qué](#4-quién-valida-qué)
5. [Restricciones que no son negociables](#5-restricciones-que-no-son-negociables)
6. [Qué tiene el simulador que tu servicio no debe copiar](#6-qué-tiene-el-simulador-que-tu-servicio-no-debe-copiar)
7. [Cómo verificar que cumple](#7-cómo-verificar-que-cumple)
8. [Cómo enchufarlo](#8-cómo-enchufarlo)

---

## 1. Por qué importa tanto

En esta plataforma **la identidad es la única fuente de verdad sobre quién puede hacer qué**.
No hay listas de permisos en ningún otro lado: ni ACLs en Kafka, ni tabla de grupos en el
gateway, ni configuración que haya que mantener sincronizada.

Eso tiene una consecuencia buena y una exigente:

- **Buena:** dar de baja un cliente en tu servicio lo deja afuera del bus *y* de la API a la
  vez, sin que nadie tenga que borrarlo de ningún otro lugar.
- **Exigente:** si tu servicio emite un token con el `namespace` equivocado, ese cliente
  publica y consume como otro grupo. No hay una segunda barrera que lo detenga.

Dos componentes independientes validan los tokens que emitas: el **event-gateway** (la API
REST) y el **broker de Kafka** (para los consumidores que se conectan directo). Los dos
contra el mismo JWKS.

---

## 2. Los dos endpoints

### `POST /oauth/token`

Flujo `client_credentials` de OAuth 2.0 ([RFC 6749 §4.4](https://www.rfc-editor.org/rfc/rfc6749#section-4.4)).

**Petición:**

```http
POST /oauth/token
Authorization: Basic base64(client_id:client_secret)
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
```

Las credenciales tienen que aceptarse **de las dos formas**: en el header `Authorization:
Basic` y en el cuerpo del formulario como `client_id` y `client_secret`.

> **El header Basic no es opcional.** El cliente de Kafka lo usa siempre: cuando un
> consumidor configura `sasl.oauthbearer.token.endpoint.url`, la librería de Kafka arma la
> petición con Basic y no se puede cambiar. Si tu servicio sólo acepta las credenciales en
> el cuerpo, la API REST va a funcionar pero **nadie va a poder conectarse a Kafka**.

**Respuesta `200`:**

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6...",
  "token_type": "Bearer",
  "expires_in": 900
}
```

`expires_in` tiene que ser un **número en segundos**. El cliente de Kafka lo lee para saber
cuándo renovar; si es una cadena como `"15m"`, falla al parsearlo.

**Errores**, con la forma del RFC:

```json
{ "error": "invalid_client", "error_description": "Las credenciales no son válidas." }
```

| Situación | Código | `error` |
|---|---|---|
| `grant_type` distinto de `client_credentials` | 400 | `unsupported_grant_type` |
| Faltan las credenciales | 400 | `invalid_request` |
| Credenciales incorrectas | 401 | `invalid_client` |

### `GET /.well-known/jwks.json`

Las claves públicas en formato [JWKS](https://www.rfc-editor.org/rfc/rfc7517), **sin
autenticación** — lo consultan el gateway y el broker al arrancar y cuando ven un `kid` que
no conocen.

```json
{
  "keys": [
    {
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "kid": "citypass-auth-key",
      "n": "…",
      "e": "AQAB"
    }
  ]
}
```

Cada clave tiene que traer su `kid`, y ese `kid` tiene que coincidir con el del header de
los tokens que firmás con ella.

### `GET /health`

Devolvé algo simple. El orquestador lo usa para saber si el servicio está listo, y el broker
de Kafka **espera a que esté sano antes de arrancar** — sin JWKS no puede validar nada.

---

## 3. El token

Firmado con **RS256**. El header lleva el `kid`:

```json
{ "alg": "RS256", "kid": "citypass-auth-key" }
```

Y el payload, estos claims. **Todos son obligatorios**:

| Claim | Valor | Quién lo usa y para qué |
|---|---|---|
| `sub` | Quién pidió el token | El gateway lo estampa en `metadata.source` de cada evento. Es la traza de quién publicó, y no se puede falsificar porque sale de acá |
| `namespace` | El identificador del grupo | **El más importante.** Delimita en qué tópicos puede publicar (`<namespace>.*`) y es la identidad con la que Kafka autoriza el consumo |
| `aud` | Tiene que contener `citypass` | Lo verifican el gateway y el broker. Evita que un token emitido para otro sistema sirva acá |
| `jti` | Único por emisión | El gateway lo estampa en `metadata.tokenId`. Permite trazar un evento hasta la emisión concreta del token |
| `iat` | Momento de emisión | |
| `exp` | Vencimiento | Lo verifican los dos validadores |

Ejemplo de payload:

```json
{
  "sub": "grupo3",
  "namespace": "com.citypass.movilidad",
  "aud": "citypass",
  "jti": "af2480cc-487f-474f-ac06-f396ad3f403d",
  "iat": 1786547143,
  "exp": 1786548043
}
```

### Sobre `sub` y `namespace`

Hoy el simulador pone el mismo valor conceptual en los dos: `sub` es `grupo3` y `namespace`
es `com.citypass.movilidad`. Están separados a propósito.

`namespace` **identifica a la aplicación**, y por eso Kafka lo usa como principal: dos
instancias del mismo grupo, aunque se autentiquen con credenciales distintas, tienen que
poder compartir el mismo `group.id` de consumidor.

`sub` **identifica a quien pidió el token**. Si tu servicio emite tokens por persona —con
login federado, cada integrante con su usuario— eso funciona sin cambios: `metadata.source`
va a decir la persona en vez del grupo, y la consulta «mis últimos eventos» del gateway pasa
a filtrar por persona automáticamente. Es una mejora, no una ruptura.

Lo que **no** puede pasar es que `namespace` varíe entre los integrantes de un mismo grupo.

### Los namespaces actuales

| Cliente | Namespace |
|---|---|
| `grupo2` | `com.citypass.auth` |
| `grupo3` | `com.citypass.movilidad` |
| `grupo4` | `com.citypass.reclamos` |
| `grupo5` | `com.citypass.emergencias` |
| `grupo6` | `com.citypass.turismo` |
| `grupo7` | `com.citypass.transporte` |
| `grupo8` | `com.citypass.analitica` |

> El de `grupo8` lo inventamos nosotros. Si el Grupo 8 prefiere otro, avisen antes de que
> haya tópicos creados: cambiarlo después deja los eventos anteriores en un namespace que
> ya no le pertenece a nadie.

---

## 4. Quién valida qué

### El event-gateway

Construye su validador contra tu JWKS y comprueba:

1. **Firma RS256** con la clave del `kid`.
2. **`exp`** — token vencido, rechazado.
3. **`aud` contiene `citypass`.**

Después lee `sub`, `namespace` y `jti`. Un token sin `namespace` recibe un `400` explícito
en vez de un `401`, porque el problema no es la autenticación sino el contenido.

### El broker de Kafka

Usa SASL con mecanismo `OAUTHBEARER` y valida contra el mismo JWKS. Y acá hay un detalle
crítico:

```yaml
KAFKA_SASL_OAUTHBEARER_SUB_CLAIM_NAME: 'namespace'
```

**El broker toma `namespace` como el principal de la conexión**, no `sub`. Consecuencias
directas para vos:

- Un token **sin `namespace` no puede ni conectarse** a Kafka. Falla en el handshake SASL,
  antes de cualquier autorización.
- El valor de `namespace` es el que el autorizador compara contra el prefijo de los tópicos
  y de los consumer groups.

También verifica `aud` y `exp`.

---

## 5. Restricciones que no son negociables

**No emitas un cliente con permisos sobre todo.** No hay namespace comodín ni usuario
administrador, y es deliberado: una credencial así es una llave maestra sobre el bus entero,
y una sola filtración lo comprometería todo. Un `*` como namespace, además, no daría acceso
a todo — no coincide con ningún prefijo, así que no daría acceso a nada.

**No cambies el formato del `namespace`.** Es el prefijo literal de los tópicos. Si emitís
`movilidad` en vez de `com.citypass.movilidad`, el grupo pierde el acceso a sus propios
tópicos sin ningún mensaje que lo explique.

**No uses HS256 ni ningún algoritmo simétrico.** El gateway y el broker sólo tienen la clave
pública, que es justamente lo que hace que puedan validar sin poder emitir.

**El `aud` tiene que incluir `citypass`.** Si tu servicio emite tokens para otros sistemas
del proyecto, poné todas las audiencias que correspondan, pero `citypass` tiene que estar.

---

## 6. Qué tiene el simulador que tu servicio no debe copiar

El simulador es correcto en el contrato y deliberadamente ingenuo en todo lo demás. Estos
cinco puntos son los que hay que hacer bien:

**1. Los clientes salen de una base de datos.** En el simulador son un objeto en el código:

```js
const CLIENTS = {
  grupo3: { secret: 'grupo3', namespace: 'com.citypass.movilidad' },
  ...
}
```

**2. Los secrets se guardan hasheados** (bcrypt, argon2), nunca en texto plano.

**3. La clave de firma persiste entre reinicios.** El simulador la genera en memoria al
arrancar, así que **cada reinicio invalida todos los tokens emitidos**. Es la limitación más
molesta en el día a día y la que más se nota cuando falta.

**4. El JWKS tiene que poder exponer varias claves a la vez**, cada una con su `kid`. Es lo
que permite rotar la clave sin cortarle el acceso a nadie: publicás la nueva, empezás a
firmar con ella, y sacás la vieja cuando vencieron todos los tokens que firmó.

**5. `expires_in` debería ser corto** — de 5 a 15 minutos. El simulador usa 8 horas por
comodidad. Los clientes renuevan solos, y un token corto es lo que hace que revocar un
cliente tenga efecto rápido: como no hay ninguna otra lista de la que borrarlo, el token
vigente es lo único que queda, y cuanto antes venza, mejor.

Además, el enunciado del proyecto pide **login federado con LDAP**, que el simulador no
tiene. Eso es tuyo: de dónde salen los usuarios y cómo se autentican es tu decisión, siempre
que el token que emitas tenga los claims de arriba.

---

## 7. Cómo verificar que cumple

Antes de integrar, corré esto contra tu servicio. Si los seis pasan, entra sin cambios.

Con `AUTH=http://localhost:8083` (o donde corra el tuyo):

```bash
# 1. Emite un token con credenciales por Basic — es la forma que usa el cliente de Kafka
curl -s -X POST $AUTH/oauth/token -u grupo3:grupo3 \
  -d grant_type=client_credentials | jq
# Esperado: access_token, token_type "Bearer", expires_in numérico
```

```bash
# 2. También acepta las credenciales en el cuerpo
curl -s -X POST $AUTH/oauth/token \
  -d grant_type=client_credentials -d client_id=grupo3 -d client_secret=grupo3 | jq .access_token
# Esperado: un JWT, no un error
```

```bash
# 3. Rechaza credenciales incorrectas con 401 y la forma del RFC
curl -s -o /dev/null -w '%{http_code}\n' -X POST $AUTH/oauth/token \
  -u grupo3:incorrecta -d grant_type=client_credentials
# Esperado: 401
```

```bash
# 4. El JWKS es público y trae kid y alg
curl -s $AUTH/.well-known/jwks.json | jq '.keys[0] | {kty, alg, kid, use}'
# Esperado: RSA, RS256, un kid, "sig"
```

```bash
# 5. El token trae los seis claims y el kid del header coincide con el del JWKS
TOKEN=$(curl -s -X POST $AUTH/oauth/token -u grupo3:grupo3 \
  -d grant_type=client_credentials | jq -r .access_token)

# Un JWT usa base64url, que no es el base64 estándar: `base64 -d` falla con los
# caracteres `-` y `_` y con el relleno ausente. Este decodificador sí lo maneja.
jwt() { python3 -c "import sys,base64,json;s=sys.stdin.read().strip();print(json.dumps(json.loads(base64.urlsafe_b64decode(s+'='*(-len(s)%4))),indent=2))"; }

echo $TOKEN | cut -d. -f1 | jwt     # header:  alg RS256 + kid
echo $TOKEN | cut -d. -f2 | jwt     # payload: los seis claims
# Esperado en el payload: sub, namespace, aud con "citypass", jti, iat, exp
```

```bash
# 6. La clave sobrevive a un reinicio — el que más se olvida
#    Reiniciá tu servicio y verificá que el token de arriba SIGA sirviendo:
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/event-types
# Esperado: 200. Si da 401, la clave se regeneró: es el punto 3 de la sección anterior.
```

Y la prueba definitiva, con el bus levantado: **conectá un consumidor de Kafka** con tu
servicio como emisor. Es lo que ejercita el camino completo —Basic, `expires_in`, JWKS,
`namespace` como principal— de una sola vez. Hay ejemplos en JavaScript, Python y Java en el
[README](../README.md#7-consumir-eventos-desde-kafka).

---

## 8. Cómo enchufarlo

No hay que tocar código del bus. Son tres variables:

```bash
# .env
AUTH_SERVICE_URL=https://identidad.citypass.tudominio.com    # lo lee el event-gateway
VITE_LOGIN_API_URL=https://identidad.citypass.tudominio.com  # lo embebe la UI en su build
```

Y en `docker-compose.yml`, el broker apunta al JWKS:

```yaml
KAFKA_SASL_OAUTHBEARER_JWKS_ENDPOINT_URL: 'https://identidad.../.well-known/jwks.json'
```

Después se puede sacar el servicio `auth-simulator` del compose.

**Ojo con dos cosas:**

El gateway y el broker resuelven el JWKS **desde adentro de la red de Docker**. Si tu
servicio corre en otro lado, esa URL tiene que ser alcanzable desde ahí, no sólo desde el
navegador.

Los **clientes de Kafka** resuelven el endpoint de token desde afuera, con su propia
`sasl.oauthbearer.token.endpoint.url`. Puede ser una URL distinta de la que usan el gateway
y el broker para el JWKS.

---

## Referencias

- `auth-simulator/src/index.js` — el contrato implementado, como referencia ejecutable
- [SECURITY.md](SECURITY.md) — qué se apoya en estos tokens
- [ARCHITECTURE.md](ARCHITECTURE.md) — dónde encaja la identidad en el sistema
- [ADR-011](adr/ADR-011-autorizacion-derivada-del-token.md) — por qué no hay ACLs y la identidad es la única fuente de verdad
- [README §3](../README.md#3-autenticación) — cómo usan los tokens los demás grupos
