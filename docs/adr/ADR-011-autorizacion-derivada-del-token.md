# ADR-011: Autorización en Kafka derivada del token, sin ACLs

**Estado:** Aceptado  
**Fecha:** 2026-08-13

---

## Contexto

Los grupos consumen eventos conectándose directamente al broker. Hay que impedir que un
grupo lea tópicos ajenos, escriba en el bus salteándose el gateway, o use el consumer group
de otro equipo — con una sola partición, un intruso en el group de la víctima hace que la
víctima deje de recibir mensajes.

La identidad la emite un servicio externo (el del Grupo 2) que puede dar de alta y de baja
clientes en cualquier momento, sin avisarle a este proyecto.

## Opciones consideradas

### 1. ACLs nativas de Kafka

Kafka autoriza por ACLs: reglas guardadas en el cluster que asocian un principal con
operaciones sobre recursos.

- Es el mecanismo estándar y está bien documentado.
- Las reglas son **estado del cluster**: hay que crearlas para cada grupo.
- Eso obliga a mantener una lista de grupos sincronizada a mano con el servicio de
  identidad. Son dos fuentes de verdad, y la que se desincroniza en silencio es la de
  seguridad: un grupo dado de baja en el emisor conserva su ACL.
- Mantenerlas sincronizadas exigiría un proceso que consulte periódicamente al emisor —un
  temporizador, o sea polling en un sistema orientado a eventos— o un evento de alta y baja
  que el emisor todavía no publica.

### 2. Un `Authorizer` propio que derive la política del token

Kafka permite reemplazar el autorizador por una implementación propia
(`org.apache.kafka.server.authorizer.Authorizer`).

- El principal de la conexión sale del JWT validado por SASL/OAUTHBEARER.
- La política se calcula en cada conexión a partir de ese principal: no hay reglas guardadas.
- Si el emisor deja de emitir tokens para un grupo, el acceso se corta solo.
- Exige escribir y mantener código que corre dentro de la JVM del broker, con el riesgo de
  que un error deje al cluster sin arrancar.

### 3. No exponer Kafka y que todos consuman por webhook

- Elimina el problema por completo.
- Obliga a todos los grupos a exponer un endpoint público y renuncia al control de offsets
  y a releer el histórico, que son las razones para usar Kafka.

## Decisión

Un **autorizador propio** (`NamespaceAuthorizer`) que deriva la política del claim
`namespace` del token, sin ACLs de ningún tipo.

Tres reglas: sólo operaciones de lectura, sólo tópicos que empiecen con el prefijo de
negocio, y sólo consumer groups que empiecen con el propio namespace.

El principal es el **namespace** y no el `sub`, porque un consumer group pertenece a la
aplicación: dos instancias del mismo grupo con credenciales distintas tienen que poder
compartir el `group.id`.

## Consecuencias

### Positivas

- **Una sola fuente de verdad.** El servicio de identidad decide quién entra, y el broker
  obedece sin guardar nada.
- No hay estado que pueda quedar desactualizado ni proceso de sincronización que mantener.
- Publicar queda cerrado por construcción: negar la escritura es una línea, no una ACL por
  grupo que alguien podría olvidar.

### Negativas

- Es código propio corriendo dentro del broker: un error de compatibilidad con la versión de
  Kafka impide que el cluster arranque. De hecho ocurrió dos veces durante el desarrollo —
  el jar compilado para una versión de Java distinta a la de la imagen, y la biblioteca
  estándar de Kotlin sin empaquetar.
- Obliga a un proyecto Gradle aparte, porque el artefacto se despliega dentro de la imagen
  del broker y no del gateway.
- Cambiar la política exige recompilar y desplegar la imagen, no un comando de
  administración.
