# Identidad — qué necesitamos del Grupo 2

El bus ya valida los tokens de login-federado en producción
(`https://api.citypass-login.pp.ua`). Nos adaptamos a su configuración actual —`iss`,
health, nombres de clientes, JWKS— sin pedirles cambios ([AUTH.md §6](AUTH.md#6-diferencias-con-lo-que-esperábamos-y-cómo-las-absorbemos)).

Quedan **dos cosas** que no podemos resolver de nuestro lado.

---

## 1. Los secretos de los clientes que ya existen

`group1` (nuestro, `com.citypass.bus`), `grupo2` y `group5`. Los necesitamos para la consola
y para probar contra producción. **Por un canal privado**, nunca en un repositorio ni en un
chat público.

## 2. Un cliente de servicio por cada grupo que use el bus

Hoy cualquier grupo que publique por la API, administre schemas, se suscriba a webhooks o
consuma Kafka directo necesita su propio token de servicio, y sin un cliente registrado no
puede obtenerlo. Es una entrada por grupo en `citypass.clients` de su `application.yml`:

```yaml
- client-id: grupo3
  type: service
  client-secret: ${SVC_GRUPO3_SECRET}
  audience: citypass
  namespace: com.citypass.movilidad
```

Los namespaces que veníamos usando:

| Cliente | Namespace |
|---|---|
| grupo3 | `com.citypass.movilidad` |
| grupo4 | `com.citypass.reclamos` |
| grupo5 | `com.citypass.emergencias` |
| grupo6 | `com.citypass.turismo` |
| grupo7 | `com.citypass.transporte` |
| grupo8 | `com.citypass.analitica` |

El nombre del cliente lo eligen ustedes; el namespace es lo que importa, porque es el
prefijo de los tópicos de ese grupo.

---

## Sólo como aviso

- `group5` hoy tiene `com.citypass.analitica`. Si ese cliente es del equipo de emergencias,
  cuando exista el de analítica los dos compartirían el mismo espacio de tópicos. No lo
  necesitamos resuelto para operar.
- `application.yml` lee `SVC_GRUPO2_SECRET` pero su `docker-compose.prod.yml` define
  `SVC_GROUP2_SECRET`, así que en producción `grupo2` probablemente está con el secreto de
  desarrollo.
