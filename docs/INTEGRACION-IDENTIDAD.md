# Integración con el servicio de identidad — pedidos al Grupo 2

Leímos la guía de integración de autenticación y adaptamos el bus a ella: **siete cambios
de nuestro lado, ya implementados** (§4). Este documento es lo que **no** podemos resolver
solos, porque depende de qué emite el servicio de identidad.

Son **cuatro puntos**. Tres son omisiones del documento más que decisiones a revertir; el
cuarto es un hueco de diseño que nos deja sin forma de atender a las personas.

> **Contexto en una línea:** el bus de eventos recibe tokens de servicio de los ocho
> módulos, valida firma, emisor, audiencia y tipo, y usa el claim `namespace` como frontera
> de autorización — un módulo sólo puede publicar en `<namespace>.*`.

---

## 1. El `aud` del token de servicio no está definido

**Qué dice la guía.** Para el token humano define la audiencia (`citypass-reclamos-api`, la
API del módulo). Para el token de servicio de §7 no la menciona.

**El problema.** Si el token de servicio llevara la audiencia del **módulo emisor**, lo
rechazaríamos. `aud` identifica al **recurso al que se accede**, no a quien lo pide: un
token para publicar en el bus tiene que declarar la audiencia del bus. Es la propiedad que
hace que un token robado de una API no sirva contra otra, y es exactamente la razón por la
que ustedes mismos piden validarla.

**Lo que pedimos.** Que el token emitido para el bus incluya en su `aud` la audiencia del
bus. Hoy esperamos el literal `citypass`.

**Por qué de su lado.** El valor concreto lo podemos cambiar nosotros —es una variable de
entorno—, así que si prefieren otro nombre lo acordamos y listo. Lo que no podemos hacer es
aceptar cualquier audiencia: eso sería desactivar el control.

---

## 2. Falta definir qué va en el `sub` del token de servicio

**Qué dice la guía.** Para el token humano es explícita y con razón: `sub` es el
identificador estable, `preferred_username` no. Para el token de servicio no dice qué es
`sub`.

**El problema.** El gateway estampa ese `sub` en `metadata.source` de **cada evento que
pasa por el bus**, y los eventos son inmutables y viven en Kafka con su retención. Si hoy
es el `client_id` y mañana un UUID, el historial queda partido en dos formatos y ningún
consumidor puede correlacionar de un lado al otro del cambio.

**Lo que pedimos.** Que definan el `sub` de los tokens de servicio y se comprometan a su
estabilidad, con el mismo criterio con el que lo hicieron para las personas.

**Por qué de su lado.** Es el emisor quien decide la identidad. Nosotros sólo la sellamos
en cada evento — y una vez sellada, no se puede corregir.

---

## 3. `https://idp.citypass.local` no es un host alcanzable

**Qué dice la guía.** El `iss` a validar literalmente es `https://idp.citypass.local`.

**El problema.** El TLD `.local` está reservado para mDNS y no resuelve entre despliegues.
Y no es sólo cosmético: **nuestro broker de Kafka y nuestro gateway tienen que descargar el
JWKS** desde afuera para validar firmas. Si el host no resuelve, no hay validación posible
y nadie puede publicar ni consumir.

**Lo que pedimos.** Un host real, con TLS válido, alcanzable desde otras instancias. Y que
nos avisen si el `iss` va a diferir de la URL desde la que se sirve el JWKS: son dos cosas
distintas y las validamos por separado.

**Por qué de su lado.** Es la dirección de su servicio.

---

## 4. El acceso de **personas** a nuestra API no está contemplado

Éste es el más grande, y el único que no es una omisión sino un hueco de diseño.

**Qué dice la guía.** El token humano lleva `aud` de **su propio módulo**, `groups` y
`module` — y **no lleva `namespace`**.

**El problema.** El bus tiene una interfaz web que usan **personas de los otros equipos**
para registrar sus event types, consultar schemas y publicar eventos de prueba. Con el
modelo actual, una persona del módulo de reclamos:

- no tiene un token cuya audiencia sea la nuestra, así que lo rechazamos por `aud`;
- y aunque lo tuviera, no trae `namespace`, así que no sabríamos sobre qué tópicos puede
  operar.

Hoy lo resolvemos con `client_credentials` desde el navegador, que es **precisamente lo que
su documento desaconseja** —y con razón: el `client_secret` es una credencial de servicio y
no debería estar en una página web.

**Lo que pedimos.** Que el token humano pueda emitirse con **la audiencia del bus** y que
lleve el claim **`namespace`**, el mismo valor que ya llevan los tokens de servicio de ese
módulo.

**Por qué de su lado, y esto es lo importante.** La alternativa sería que nosotros
derivemos el namespace concatenando `com.citypass.` + el claim `module`. Nos negamos a
hacerlo, y no por comodidad:

> Una frontera de autorización armada por quien la va a hacer cumplir no es una frontera.
> Si el bus construye el namespace pegando strings, cualquier cambio de su lado en cómo se
> nombran los módulos se convierte en un fallo de autorización del nuestro —y en el peor
> caso, en que alguien publique en el namespace de otro equipo.

La frontera tiene que llegar **firmada**. Es la misma razón por la que ustedes piden que
`aud` se valide en vez de deducirse, y por la que nuestro
[ADR-011](adr/ADR-011-autorizacion-derivada-del-token.md) deriva toda la autorización de
Kafka del token y de ningún otro lado.

### Un detalle que se desprende

Si el token humano llega a nuestra API, hay que decidir **qué puede hacer una persona** ahí:
nuestra postura es que pueda **administrar event types de su namespace** pero **no
publicar** — publicar sigue siendo cosa de un backend con `token_use: service`, y así se
mantiene intacta la regla de frontera de su §7. Si les cierra, lo implementamos de nuestro
lado sin pedirles nada más.

---

## 5. Lo que ya adaptamos de nuestro lado

Para que quede claro que el reparto no es «cambien ustedes»: estos siete cambios ya están
implementados contra su especificación.

| # | Qué | Por qué era nuestro |
|---|---|---|
| 1 | Validamos `iss` literalmente | Es una validación nuestra sobre un claim que ustedes ya emiten |
| 2 | Nuestro simulador ahora emite `iss` | Un doble de prueba que no emite lo que emite el real hace que los tests pasen y producción falle |
| 3 | Validamos `token_use: service` | Hoy rechazábamos tokens humanos **por accidente**, no por control |
| 4 | Validamos `ver` | Rechazar lo que no se entiende es mejor que interpretarlo con reglas de otra versión |
| 5 | TTL del simulador a 15 min, y renovación en la interfaz | Con tokens de 8 horas el vencimiento no se ejercitaba nunca; con los suyos, la interfaz habría echado a la gente cada 15 minutos |
| 6 | Configuramos el refresco del JWKS en Kafka | El validador de Kafka recarga por intervalo, no ante un `kid` desconocido: una rotación de clave nos habría dejado rechazando conexiones hasta una hora |
| 7 | Adoptamos `actorSub` como convención documentada | Su §7 la propone y nos parece correcta; la escribimos en nuestro contrato de eventos para que todos los módulos usen el mismo nombre |

Los detalles están en [AUTH.md](AUTH.md) y en
[CONTRACTS.md](CONTRACTS.md#el-actor-humano-detrás-de-un-evento).

---

## 6. Lo que nos pareció bien resuelto

Vale decirlo, porque no es lo habitual encontrarlo:

- **`aud` siempre como lista.** Nos ahorró un bug: veníamos validando lista, pero muchos
  equipos van a escribir `token.aud === "..."` y su documento se anticipa.
- **La regla de frontera de §7** —autorizar a la persona con su token, publicar con el de
  servicio, y que la identidad viaje como dato— es exactamente nuestro diseño, escrita
  mejor de lo que la teníamos nosotros.
- **`namespace` en el token de servicio, con nuestros valores.** Se nota que leyeron el
  contrato del bus antes de escribir el suyo.
- **Que el token no se pueda revocar y que la mitigación sea la vida corta**, dicho de
  frente en vez de escondido.

---

## Referencias

- [AUTH.md](AUTH.md) — qué claims exigimos y quién valida cada uno
- [CONTRACTS.md](CONTRACTS.md) — el contrato de eventos, incluida la convención `actorSub`
- [ADR-011](adr/ADR-011-autorizacion-derivada-del-token.md) — por qué la autorización se
  deriva del token y no se construye
