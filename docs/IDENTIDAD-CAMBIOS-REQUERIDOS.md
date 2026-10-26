# Identidad — qué necesitamos del Grupo 2

El bus valida los tokens de login-federado (`https://api.citypass-login.pp.ua`). Nos
adaptamos a su configuración actual —`iss`, health, nombres de clientes— sin pedirles
cambios ([AUTH.md §6](AUTH.md#6-diferencias-con-lo-que-esperábamos-y-cómo-las-absorbemos)).
Los clientes de servicio `grupo1` a `grupo8`, con los namespaces del bus, ya están en su
`main` ([AUTH.md §3](AUTH.md#los-clientes-de-servicio)).

---

## Lo único abierto: los secretos

Cada grupo necesita el secreto de su cliente `grupoN` para publicar, administrar schemas,
suscribirse a webhooks o consumir Kafka. Se entrega **por un canal privado**, nunca en un
repositorio ni en un chat público. El de `grupo1` es el nuestro, para la consola y para
probar contra producción.

---

## Para más adelante (login personal en la consola)

No hace falta para esta integración. Queda anotado para cuando la consola pase al login
humano:

- **`namespace` en el token humano:** está en su rama
  `task-147-agregacion-del-campo-namespace-al-jwt-human`, sin mergear.
- **Un cliente humano para la consola:** `citypass-bus-web`, transversal y con
  `audience: citypass`, igual que su `citypass-admin-web`. Sin él sólo podrían loguearse
  personas de la OU Eda.
