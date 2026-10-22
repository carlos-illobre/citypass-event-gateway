# ADR-024: La identidad la emite login-federado (Grupo 2); se retira el auth-simulator

**Estado:** Aceptado
**Fecha:** 2026-10-22

> Lleva el 024 porque el índice ya reserva el 022 y el 023.

---

## Contexto

Desde el principio el bus validó tokens de servicio con un contrato fijo —RS256, `aud`
`citypass`, `namespace`, `token_use: service`, `ver: 1`— y para desarrollar sin depender de
nadie traía su propio emisor, `auth-simulator`: un Node de 200 líneas con ocho clientes
hardcodeados (secreto igual al id) y una clave RSA que se regeneraba en cada arranque.

El Grupo 2 publicó su servicio de identidad, **login-federado** (Spring + LDAP), en
`https://api.citypass-login.pp.ua`. Para tokens de servicio expone el mismo
`POST /oauth/token` (`client_credentials`, Basic o cuerpo), un JWKS público y emite los
mismos claims. El login humano contra LDAP (`/auth/login`) existe, pero emite tokens
`token_use: human` sin `namespace`, que el bus rechaza por diseño
([AUTH.md §3](../AUTH.md#por-qué-se-exige-token_use-service)).

Lo que no coincide con lo que esperábamos:

- `iss` es `https://idp.citypass.local`, un nombre que no resuelve.
- `/health` pide token; el público es `/actuator/health`.
- El JWKS no trae `alg` ni `use`.
- Sólo hay tres clientes de servicio (`group1`, `grupo2`, `group5`), con nombres distintos
  de los del simulador.

## Decisión

1. **Gateway, dispatcher y broker validan contra el JWKS de login-federado.** La URL deja de
   estar escrita en el compose y sale de `AUTH_SERVICE_URL` en el `.env`; `TOKEN_ISSUER`
   toma el `iss` que emiten hoy.
2. **Nos adaptamos a su configuración en vez de pedir cambios.** Las diferencias se absorben
   de nuestro lado (`TOKEN_ISSUER` literal, `/auth/health` traducido a `/actuator/health`,
   validadores que no necesitan `alg`/`use`). Al Grupo 2 sólo se le pide lo que no se puede
   resolver acá: los secretos y los clientes que faltan
   ([IDENTIDAD-CAMBIOS-REQUERIDOS.md](../IDENTIDAD-CAMBIOS-REQUERIDOS.md)).
3. **Se mantiene `/auth/` en nuestros proxies**, ahora reenviando por HTTPS (con verificación
   de certificado) a login-federado. La consola y los grupos que ya usaban
   `https://<dominio>/auth/oauth/token` no cambian nada, y el navegador nunca le habla
   directo, así que no hace falta CORS del lado de ellos.
4. **La consola sigue con `client_credentials`.** No se integra el login humano de LDAP.
5. **Se borran `auth-simulator` y `event-gateway-ui`**, que sólo sabía hablar con él y ya
   estaba reemplazada por `frontend`.

## Opciones descartadas

**Mantener el simulador para desarrollo local.** Dos emisores con el mismo contrato pero
datos distintos (clientes, secretos, `iss`) son dos configuraciones a mantener, y la de
desarrollo dejaría de parecerse a la real justo en lo que más se rompe: nombres de clientes
y `iss`. Las pruebas se hacen contra producción del Grupo 2.

**Levantar login-federado local** (su compose con OpenLDAP y Postgres). Sirve para probar su
código, no el despliegue real; y para tokens de servicio no aporta nada, porque los clientes
no viven en LDAP sino en su `application.yml`.

**Pedirle al Grupo 2 que ajuste `iss`, `/health` y el JWKS a lo que esperábamos.** Cada
cambio en su lado es una coordinación más y un riesgo de que lo cambien de nuevo sin avisar.
Ninguno de los tres es necesario para validar correctamente.

**Integrar el login humano en la consola** derivando el namespace de `module`. Obliga a
aflojar `token_use: service`, mantener una tabla `module → namespace` de nuestro lado (justo
lo que [ADR-011](ADR-011-autorizacion-derivada-del-token.md) evita) y no resuelve el uso
servicio a servicio, que es el que importa.

## Consecuencias

- **Dependencia externa en caliente.** Si login-federado cae, nadie obtiene tokens nuevos;
  los vigentes valen hasta su `exp` (15 min). No hay `depends_on` posible.
- **Salida HTTPS obligatoria** desde la instancia a `api.citypass-login.pp.ua`, para
  gateway, dispatcher, broker y los dos nginx.
- **Los secretos son reales.** Los tests de integración y el seed de la consola los leen del
  entorno (`CLIENT_SECRET_<CLIENTE>`, `SEED_CLIENT_SECRET`), nunca de un archivo versionado.
- **Grupos sin cliente registrado no pueden operar** hasta que el Grupo 2 lo cree. Del lado
  del bus no hay nada que dar de alta: el acceso sale entero del token.
- Si el Grupo 2 cambia el `iss`, todo da 401 hasta actualizar `TOKEN_ISSUER`. Si rota la
  clave, el `kid` nuevo dispara la relectura del JWKS y no hay que hacer nada.
