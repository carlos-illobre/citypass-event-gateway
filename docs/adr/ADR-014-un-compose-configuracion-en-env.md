# ADR-014: Un solo docker-compose, la configuración por ambiente en el .env

**Estado:** Aceptado  
**Fecha:** 2026-08-13

---

## Contexto

El sistema corre en dos ambientes muy distintos. En desarrollo, cada servicio publica su
puerto y se accede directo por HTTP. En producción sólo el reverse-proxy escucha, hay TLS,
y varios comportamientos del gateway se endurecen.

Hacía falta una forma de expresar esa diferencia sin que el ambiente equivocado quede
configurado por accidente.

## Opciones consideradas

### 1. Dos archivos de compose

Un `docker-compose.yml` y un `docker-compose.prod.yml` que lo sobrescribe.

- Es el patrón más común y el más documentado.
- Se comprobó que **Compose concatena las listas de `ports` en vez de reemplazarlas**: un
  overlay puede agregar puertos publicados, nunca quitarlos. Eso obliga a que el archivo
  base sea el de producción y el overlay el de desarrollo.
- Con `docker-compose.override.yml` el desarrollo es cómodo pero producción tiene que
  acordarse de pasar los `-f` correctos, y olvidarse publica todos los puertos en silencio.
- Duplica la definición de los servicios que cambian, con el riesgo de que las dos copias se
  desincronicen.

### 2. Un solo compose, todo parametrizado por el `.env`

- La diferencia entre ambientes vive en un único lugar.
- La interfaz de publicación se parametriza: `${PUBLISH_ADDR:-127.0.0.1}:8080:8080`. El
  default es loopback, o sea que una variable que falte **cierra** en vez de abrir.
- Los servicios que sólo existen en producción se activan con `COMPOSE_PROFILES`, que
  Compose lee del propio `.env`.
- No se puede hacer desaparecer un puerto publicado, sólo moverlo: por eso el broker se
  corre a otro puerto en producción, donde el 9092 lo ocupa el proxy.

### 3. Un generador o plantillas

Producir el compose desde una plantilla según el ambiente.

- Máxima flexibilidad.
- Agrega un paso de build y un artefacto generado que puede quedar desactualizado respecto
  de su fuente.

## Decisión

**Un solo `docker-compose.yml`**, con toda la diferencia entre ambientes en archivos
`.env.*` que declaran exactamente las mismas variables con distintos valores: `.env.example`
en el repositorio, y uno por proveedor de despliegue fuera de él.

Los defaults del compose son siempre los de **producción**. Los ajustes del gateway se
agrupan además bajo un único interruptor, el perfil de Spring `development`: así no puede
quedar un estado a medias con dos ajustes aflojados y uno no.

## Consecuencias

### Positivas

- Olvidarse de una variable nunca abre nada: a lo sumo hace que en local no se llegue a un
  puerto, y eso se nota enseguida.
- Un `diff` entre dos `.env.*` muestra exactamente qué cambia entre ambientes.
- La configuración insegura de desarrollo vive en un archivo que producción no carga, en vez
  de estar en el compose protegida por un comentario que dice «no copiar esto».

### Negativas

- El puerto del broker tiene que ser distinto en producción, lo que es una asimetría que hay
  que explicar.
- `COMPOSE_PROFILES` acepta en silencio un perfil inexistente: un error de tipeo no levanta
  el proxy y no avisa. Falla cerrado —el despliegue queda inalcanzable— pero el diagnóstico
  no es obvio, y por eso está documentado en el propio `.env`.
- Nada impide que los dos archivos se desincronicen, así que `test-integration.sh` comprueba
  que declaren el mismo conjunto de variables.
