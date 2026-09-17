> ## Documento externo
>
> **Lo escribió el Grupo 2 (identidad), no este equipo.** Se guarda acá tal como lo
> recibimos, para tener el contrato con el que trabajamos y poder detectar cuándo cambia.
> No editar: si algo hay que corregir, se pide allá.
>
> - Lo que **necesitamos que cambien** está en
>   [IDENTIDAD-CAMBIOS-REQUERIDOS.md](../IDENTIDAD-CAMBIOS-REQUERIDOS.md).
> - La **justificación de cada pedido**, en [INTEGRACION-IDENTIDAD.md](../INTEGRACION-IDENTIDAD.md).
> - Lo que **el bus exige y valida**, en [AUTH.md](../AUTH.md).

---

# CityPass+ — Autenticación e identidad: guía de integración

**Audiencia: los equipos de desarrollo de los módulos.**

Esto es todo lo que necesitás para integrar el login de CityPass+ en tu módulo. No hace
falta saber nada de cómo funciona el proveedor de identidad por dentro.

---

## 1. La idea general

Tu módulo **no maneja contraseñas**. Nunca. El login lo hace el servicio de identidad (el
IdP, del Grupo 2), y a cambio te da un **token**: una credencial firmada digitalmente que
dice quién es la persona y a qué grupos pertenece. Tu backend valida ese token en cada
request —sin llamarnos, con nuestra clave pública— y decide qué dejarle hacer a la persona
según sus grupos.

```
Persona → se loguea contra el IdP → recibe un token
        → tu frontend manda el token en cada request a tu backend
        → tu backend lo valida y autoriza
```

División de trabajo, en una línea: **nosotros decimos QUIÉN ES y EN QUÉ GRUPOS ESTÁ; tu
módulo decide QUÉ PUEDE HACER.**

El token no trae permisos. Trae grupos. Si tu código pregunta "¿está en `soporte-n2`?" y en
base a eso permite cerrar tickets, lo estás usando bien. Los grupos los administra el
delegado de tu módulo desde un panel; qué significa cada grupo lo programás vos.

---

## 2. Los endpoints

| Endpoint | Para qué |
| --- | --- |
| `POST /auth/login` | Usuario + contraseña → tokens |
| `POST /auth/refresh` | Renovar el access token sin pedir contraseña |
| `POST /auth/logout` | Cerrar la sesión |
| `GET /.well-known/jwks.json` | Claves públicas para validar (público) |
| `POST /oauth/token` | Token de servicio para backends (ver §7) |

### Login

```
POST /auth/login
{
  "username": "jperez",
  "password": "...",
  "client_id": "citypass-reclamos-web"
}
```

El `client_id` identifica a tu aplicación; te lo asignamos nosotros junto con tu audience
(ver §4, `aud`).

Respuesta:

```json
{
  "access_token": "eyJhbGciOi...",
  "refresh_token": "d4c1a7f0...",
  "token_type": "Bearer",
  "expires_in": 900
}
```

**Cualquier fallo devuelve siempre el mismo error genérico**: usuario inexistente,
contraseña incorrecta, cuenta deshabilitada o usuario de otro módulo son indistinguibles. Es
a propósito (impide averiguar qué usuarios existen), así que no intentes mostrar mensajes
distintos por causa: no hay información para hacerlo.

### Los dos tokens

- **Access token** (15 minutos): es el JWT. Va en cada request a tu backend:
  `Authorization: Bearer <token>`.
- **Refresh token** (8 horas): un string opaco. No es un JWT, no contiene nada, no se manda
  a tu backend. Sirve solo para pedir un access token nuevo cuando el actual vence.

### Refresh — lo que tu frontend tiene que hacer

```
POST /auth/refresh
{ "refresh_token": "d4c1a7f0..." }
```

Devuelve un par nuevo — **access token nuevo Y refresh token nuevo**. El refresh viejo queda
inutilizado en el momento del canje: **guardá siempre el último par**. Si tu código sigue
usando el anterior, el sistema lo interpreta como un token robado y cierra todas las
sesiones de esa persona.

El flujo estándar en el frontend: request → si responde 401 por token vencido →
`/auth/refresh` → guardar el par nuevo → reintentar el request original. La persona no ve
nada de esto.

Si el propio refresh devuelve 401, la sesión terminó de verdad (pasaron las 8 horas, la
cerraron, o deshabilitaron la cuenta): mandá a la persona a la pantalla de login.

### Logout

```
POST /auth/logout
{ "refresh_token": "d4c1a7f0..." }
```

Invalida la sesión. El access token vigente puede seguir funcionando hasta 15 minutos; tu
frontend simplemente lo descarta.

---

## 3. El token humano por dentro

```json
{
  "iss": "https://idp.citypass.local",
  "sub": "U000042",
  "aud": ["citypass-reclamos-api"],
  "token_use": "human",
  "ver": 1,
  "preferred_username": "jperez",
  "module": "reclamos",
  "groups": ["soporte-n2", "guardia-finde"],
  "iat": 1754600000,
  "exp": 1754600900,
  "jti": "7c9e6679-7425-40de-944b-e07fc1f90ae7"
}
```

| Campo | Qué es | Qué hacés con él |
| --- | --- | --- |
| `iss` | Quién emitió el token | Validar que sea **exactamente** este texto |
| `sub` | **El identificador estable de la persona** | Esto es lo que guardás en tu base |
| `aud` | Para qué API se emitió. **Siempre una lista** | Validar que la tuya esté adentro |
| `token_use` | `human` o `service` | Validar que sea el que tu endpoint espera |
| `ver` | Versión del contrato | Debe ser el número `1` |
| `preferred_username` | Nombre de usuario, para mostrar | Mostrarlo en pantalla. **Nunca usarlo como clave** |
| `module` | Módulo de la persona | Informativo (tu `aud` ya lo implica) |
| `groups` | Sus grupos | Tu lógica de permisos |
| `iat` / `exp` | Emitido / vence (epoch, segundos) | Validar `exp` |
| `jti` | ID único de este token | Guardarlo en logs de auditoría. Opaco |

### Las cuatro reglas que más se rompen

**1. `sub` es la clave; `preferred_username` no.** El nombre de usuario puede cambiar (un
apellido mal cargado, un cambio de nombre). `sub` no cambia jamás. Si guardás `"jperez"`
como autor de algo y lo renombran, perdiste el vínculo. Guardá `"U000042"`.

**2. `aud` es siempre una lista**, aunque tenga un solo elemento. `token.aud === "mi-api"`
falla; lo correcto es "¿mi audience está *dentro* de la lista?".

**3. `groups` puede venir vacío, y es válido.** La persona se autenticó bien pero no tiene
grupos asignados todavía. No es un error de login: mostrale "no tenés accesos asignados,
hablá con tu delegado", no una pantalla rota.

**4. Los cambios de grupos tardan hasta 15 minutos en verse.** El token es una foto del
momento en que se emitió. Si el delegado te suma a un grupo, lo ves en tu próximo token
(máximo 15 min, o cerrando y abriendo sesión). Es esperado; no reportes bug.

---

## 4. Cómo validar el token en tu backend

**Regla número uno: siempre `verify()`, nunca `decode()`.** Todas las librerías JWT tienen
las dos funciones. `decode()` te da el contenido **sin chequear la firma**: un atacante edita
el token, se agrega el grupo que quiera, y tu `decode()` se lo cree. `verify()` chequea la
firma primero. Si esto sale mal, todo lo demás da igual.

Los siete pasos, en orden (tu librería hace la mayoría si la configurás bien):

1. **Resolver la clave de firma.** El encabezado del token trae un `kid` (ID de clave);
   buscá esa clave en nuestro JWKS (`/.well-known/jwks.json`). Usá la función de tu librería
   que hace esto con cache — no descargues el JWKS en cada request.
2. **Lista blanca de algoritmos: solo `RS256`.** Explícita. Es lo que bloquea el ataque
   clásico donde el token declara en su propio encabezado "no hace falta verificarme"
   (`alg: none`). Lista blanca, nunca negra.
3. **`iss` exacto.** Comparación literal, nada de "empieza con".
4. **Tu audience está en `aud`.** Un token de otro módulo se rechaza aunque la firma sea
   perfecta.
5. **`exp` en el futuro**, con 30–60 segundos de tolerancia por relojes desfasados.
6. **`token_use` es el esperado.** Un endpoint de personas rechaza tokens de servicio y
   viceversa.
7. **Tipos de lo que uses para autorizar**: `groups` es lista de strings, `sub` es string no
   vacío, `ver` es el número `1`.

### Dos trampas puntuales

**Exigí que `exp` exista.** En el estándar JWT es opcional, y varias librerías **saltean el
chequeo de expiración si el campo no está** — un token sin `exp` sería eterno. Casi todas
tienen una opción de "claims requeridos": ponele `exp`.

**Rechazá `aud` si llega como texto suelto en vez de lista.** Nuestro contrato dice lista,
siempre. Si llega otra cosa, algo anda mal aguas arriba y conviene que falle ruidosamente.

### Referencia

Hay una implementación de referencia en TypeScript que aplica todo esto, y una batería de
tokens de prueba (válido, vencido, firma inválida, audience ajena, `alg: none`) para correr
contra tu implementación en cualquier lenguaje.

**El contrato es este documento, no el código de referencia.**

---

## 5. Autorización: qué hacer con `groups`

Recomendación simple: una tabla (en tu base o en tu código) que mapee grupo → permisos:

```
soporte-n2     → ver todos los tickets, cerrarlos
guardia-finde  → reasignar fuera de horario
consulta       → solo lectura
```

Tres reglas:

- **Nada de lógica sobre el nombre.** No hagas "si termina en `-admin`, es administrador".
  Los nombres los define el delegado de tu módulo y puede cambiar de criterio.
- **Grupo desconocido = no otorga nada.** Si aparece un grupo que tu tabla no conoce,
  ignoralo. No falles ni asumas un permiso por defecto.
- **Coordiná los nombres con tu delegado.** Tu código compara letra por letra. Los nombres
  válidos son solo minúsculas, números y guiones (el panel no permite otra cosa), así que
  `Soporte N2` no puede existir — pero `soporte-n2` y `soporte-nivel-2` son grupos
  distintos.

---

## 6. Lo que el token NO hace (para que no te sorprenda)

**No se puede revocar.** Se valida offline contra la clave pública; nadie nos consulta por
token. Robado, vale hasta que expira. La mitigación es la vida corta: 15 minutos.

**Es legible.** Está firmado, no cifrado: cualquiera que lo tenga puede leer los campos
(probalo en jwt.io). La firma garantiza que nadie lo modificó, no que nadie lo vea. Por eso
no lleva datos sensibles, y por eso tampoco debés loguearlo entero: logueá el `jti`.

**No sabe nada de otros módulos.** Trae los grupos del módulo de la persona y nada más. Cada
persona pertenece a exactamente un módulo.

---

## 7. Tokens de servicio: backend a backend

Para cuando tu **backend** necesita identificarse ante otro sistema (típicamente el bus de
eventos del Grupo 1) sin que haya ninguna persona en el medio.

Te damos un `client_id` y un `client_secret` — credenciales de tu servicio, guardalas como
guardás la contraseña de tu base de datos. Se canjean así:

```
POST /oauth/token
Authorization: Basic base64(client_id:client_secret)
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
```

El token resultante tiene `token_use: "service"`, tu `namespace` (ej.
`com.citypass.reclamos`, tu frontera en el bus) y **no tiene `groups`**: un servicio no es
una persona, no pertenece a equipos; tiene una única función y esa función es su namespace.

**La regla de frontera, importante:** cuando una persona hace algo que dispara un evento, tu
backend autoriza a la persona con su token humano, y publica el evento con **su propio token
de servicio**. La identidad de la persona viaja como dato del evento (un campo `actorSub`
con su `sub`), nunca reenvíes el token humano al bus. Los tokens de las personas son para tu
API, no para pasarlos de mano en mano.

---

## 8. Preguntas frecuentes

**¿Dónde se crean los usuarios y grupos de mi módulo?**
En el panel de administración, y lo hace el delegado de tu módulo — una persona de tu grupo
con ese acceso. Tu código no crea usuarios ni grupos; los consume.

**¿Puedo pedir la lista completa de usuarios o grupos de mi módulo?**
Por ahora no hay endpoint para eso: tu módulo conoce a las personas a medida que van
entrando (y ahí guardás su `sub`). Si tenés un caso de uso que lo necesita, hablalo con el
Grupo 2 en vez de scrapearlo.

**¿Qué pasa si el servicio de identidad se cae?**
Los logins y refresh fallan, pero **los tokens ya emitidos siguen siendo válidos** hasta
expirar, porque los validás vos con la clave pública. Tu módulo sigue funcionando con la
gente ya adentro durante esos minutos.

**¿Cada cuánto descargo el JWKS?**
No lo descargues por request: usá la utilidad con cache de tu librería. Ante un `kid`
desconocido, las librerías refrescan solas.

**Deshabilitaron a alguien, ¿cuándo pierde el acceso?**
Su access token vigente dura hasta 15 minutos más. Su próximo refresh falla. Máximo: 15
minutos.

**¿Puedo meter información propia en el token?**
No. El token lo emite el IdP y su contenido es fijo. Lo que tu módulo sepa de la persona
(preferencias, historial) vive en tu base, indexado por `sub`.
