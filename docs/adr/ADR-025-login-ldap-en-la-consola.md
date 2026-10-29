# ADR-025: La consola entra con el login de personas (LDAP) de login-federado

**Estado:** Aceptado
**Fecha:** 2026-10-29

Supersede la decisión 4 del [ADR-024](ADR-024-identidad-delegada-en-login-federado.md)
(«la consola sigue con `client_credentials`»).

---

## Contexto

Con el ADR-024 la consola quedó entrando con la cuenta de servicio del equipo: `grupo1` y su
secreto. Eso tiene tres problemas: el secreto lo conoce todo el equipo, todo lo que se hace
desde la consola queda firmado como `grupo1` sin distinguir persona, y dar de baja a alguien
obliga a rotar el secreto y avisarle al resto.

login-federado ya autentica personas contra LDAP (`/auth/login`, refresh rotativo de 8 h).
Hasta ahora su token humano no servía para el bus por dos motivos: no traía `namespace`, y
su `aud` era la de la API del módulo (`citypass-reclamos-api`), no la del bus. Además, el
login sólo deja entrar a una persona por la aplicación de su propio módulo.

A pedido nuestro, el Grupo 2 resolvió las dos cosas en su `main` (PR #48 y #50):

- el token humano trae `namespace`, derivado del módulo LDAP de la persona
  (`eda` → `com.citypass.bus`, el resto → `com.citypass.<módulo>`);
- registró `citypass-bus-web`, un cliente humano **transversal** (acepta personas de
  cualquier módulo) con `audience: citypass`.

## Decisión

1. **Gateway y dispatcher aceptan `token_use` `service` o `human`.** El resto de la
   validación no cambia: firma, `exp`, `iss`, `ver` y `aud` con `citypass`. Un token humano
   emitido por la app de otro módulo sigue rechazado por `aud`.
2. **Una persona tiene en la consola los mismos permisos que el backend de su grupo**,
   acotados por el `namespace` del token: administrar event types, publicar, suscripciones
   y cola de fallidos. No se agrega ninguna regla por `groups`.
3. **La consola entra sólo por LDAP**, siempre con `clientId: citypass-bus-web`. Las cuentas
   `grupoN` + secreto quedan para backends, consumidores de Kafka, tests y seed.
4. **La sesión vive en memoria.** El access token (15 min) se renueva solo un minuto antes de
   vencer con el refresh, que rota en cada canje; recargar la página obliga a volver a
   ingresar. El logout revoca el refresh.
5. **Los proxies le pasan `X-Forwarded-For` a login-federado**, que lo usa para su detector
   de anomalías y su registro de intentos.

## Opciones descartadas

**Seguir con la cuenta compartida.** Es lo que se reemplaza; ver el contexto.

**Un backend intermedio (BFF) que guarde el secreto de `grupo1`.** Daba login por persona sin
tokens humanos en el bus, pero con un servicio más para construir y desplegar. Con el
`namespace` en el token humano dejó de hacer falta.

**Usar las aplicaciones de módulo que ya existían** (`citypass-<módulo>-web`), con un selector
de módulo en el login y una lista de siete audiencias aceptadas. No requería nada del Grupo 2,
pero el token emitido así lleva la audiencia de la API de otro grupo y también sirve contra
ella.

**Restringir la escritura al grupo LDAP `delegados`.** Agregaba de nuestro lado una regla de
permisos que hoy no existe ([ADR-011](ADR-011-autorizacion-derivada-del-token.md)). Se puede
sumar después si hace falta.

**Validar `token_use` en el broker de Kafka.** Ver la consecuencia de abajo.

## Consecuencias

- **Trazabilidad por persona.** Lo hecho desde la consola queda con el `sub` de la persona
  (su employeeNumber, `U000008`) en `metadata.source` y `createdBy`. «Mis eventos» en la
  consola muestra los de esa persona, no los del backend del grupo.
- **Cualquier persona del módulo opera su namespace entero.** Las altas y bajas se hacen en
  LDAP, del lado del Grupo 2.
- **Riesgo aceptado en Kafka.** El broker no mira `token_use` y el token humano trae
  `aud: citypass`, así que también lo aceptaría. Como el autorizador deja leer cualquier
  tópico `com.citypass.*` a quien esté autenticado, una persona con un cliente Kafka propio
  podría consumir los eventos de todos los namespaces (no escribir, ni usar el consumer group
  de otro). Requiere programar el login LDAP en el cliente, porque las librerías estándar
  sólo hacen `client_credentials`. Se aceptó por simplicidad; cerrarlo es un validador de
  `token_use` en el `kafka-authorizer`.
- **Dependencia mayor del Grupo 2.** Si login-federado o su detector de anomalías está caído,
  nadie puede ingresar a la consola. Las sesiones abiertas siguen hasta que falle una
  renovación.
- **El admin-global** (OU `Login`) entraría con `com.citypass.login`, un namespace sin
  tópicos: puede entrar pero no opera nada del bus.
