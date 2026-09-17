# Identidad — qué necesitamos del Grupo 2

Cuatro cosas. Lo demás de su guía ya lo implementamos de nuestro lado.

---

## 1. Publicar el payload del token de servicio

Su §3 muestra el ejemplo del token humano; §7 no muestra el del token de servicio. Éste es
el que necesitamos, y así es como lo esperamos:

```json
{
  "iss": "https://idp.citypass.<host-real>",
  "sub": "<definir>",
  "aud": ["citypass"],
  "token_use": "service",
  "ver": 1,
  "namespace": "com.citypass.reclamos",
  "iat": 1786547143,
  "exp": 1786548043,
  "jti": "af2480cc-487f-474f-ac06-f396ad3f403d"
}
```

## 2. `aud` = la audiencia del bus

El token para publicar tiene que declarar **`citypass`**, no la audiencia del módulo emisor
(`citypass-reclamos-api`). Un token con la audiencia equivocada lo rechazamos.

El literal lo podemos cambiar nosotros: si prefieren otro nombre, lo acordamos.

## 3. Definir el `sub` del token de servicio

Lo estampamos en `metadata.source` de **cada evento**, y los eventos son inmutables. Hace
falta saber qué es y que no cambie de formato.

## 4. Un host del IdP que resuelva

`https://idp.citypass.local` no sirve: `.local` es mDNS y no resuelve entre despliegues.
Nuestro broker y nuestro gateway **descargan el JWKS desde afuera**.

Avisen también si el `iss` va a ser distinto de la URL que sirve el JWKS: los validamos por
separado.

## 5. Token humano con acceso al bus

Que una persona pueda obtener un token con:

- **`aud`** que incluya la audiencia del bus, y
- el claim **`namespace`**, el mismo valor que ya lleva el token de servicio de su módulo.

Sin eso, una persona de otro módulo no puede usar nuestra interfaz web: falla por `aud`, y
aunque no fallara, sin `namespace` no sabemos sobre qué tópicos puede operar.

Con ese token la persona podrá **administrar event types de su namespace, no publicar**.
Publicar seguirá exigiendo `token_use: service`, así que la regla de frontera de su §7 no
cambia.

---

## Lo que ya hicimos nosotros

Validamos `iss`, `token_use` y `ver`; aceptamos `aud` como lista; bajamos el token de
nuestro simulador a 15 minutos y agregamos renovación en la interfaz; configuramos el
refresco del JWKS en Kafka; y adoptamos `actorSub` como convención para la identidad de la
persona dentro del evento.

---

## Si necesitan la justificación

Está en **[AUTH.md](AUTH.md)**: qué claim exige cada componente, quién lo valida y por qué.
El desarrollo de estos cinco puntos —qué pasa si no se cumplen y por qué el cambio
corresponde de un lado u otro— está en
[INTEGRACION-IDENTIDAD.md](INTEGRACION-IDENTIDAD.md).
