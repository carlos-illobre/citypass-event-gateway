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

## Ya resuelto: login personal en la consola

El Grupo 2 agregó `namespace` al token humano y registró el cliente `citypass-bus-web`
(transversal, `audience: citypass`) en su `main` (PR #48 y #50). La consola entra con el
usuario de LDAP de cada persona ([ADR-025](adr/ADR-025-login-ldap-en-la-consola.md)). Para
probarla hace falta una persona dada de alta en LDAP: la crea el Grupo 2 o un `delegados`
del módulo desde su panel.
