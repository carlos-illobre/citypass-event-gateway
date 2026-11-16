# ADR-025: El 9092 se protege por frecuencia de conexiones nuevas y por identidad, no por conexiones abiertas por IP

**Estado:** Aceptado
**Fecha:** 2026-11-16

---

## Contexto

El servicio de Reclamos corre en Render (región Oregon) y no podía conectarse a Kafka en el
entorno de test. El TCP abría, el token se obtenía bien por el 443, pero la conexión al 9092
se cortaba durante el handshake TLS, de forma intermitente: en días de intentos conectó una
sola vez, de madrugada. Desde una máquina local y desde GitHub Actions, con el mismo cliente,
conectaba siempre.

La causa, confirmada en los logs del reverse-proxy (`limiting connections by zone
"kafka_conexiones"`), era el límite de **10 conexiones simultáneas por IP** del bloque
`stream` de nginx:

- `limit_conn` se evalúa al aceptar la conexión, **antes** del TLS. Si la IP ya tiene 10,
  nginx cierra el socket sin leer el ClientHello que el cliente ya mandó, y el kernel
  responde con un reset. Del lado del cliente se ve como un corte en pleno handshake.
- Render saca el tráfico de todos sus servicios de una región por **unas pocas IPs
  compartidas**. Para nginx, todos los equipos alojados ahí son "una IP". Cada consumidor de
  Kafka abre entre 2 y 3 conexiones (bootstrap, datos y coordinador del grupo), así que con
  tres o cuatro servicios conectados el cupo se llena, y entra quien encuentra un hueco.

El límite existía por una razón válida, documentada en [SECURITY.md](../SECURITY.md#límites):
la conexión al 9092 se acepta antes de que el cliente se identifique, y como nginx tenía un
único worker con 1024 conexiones **compartidas con el 443**, agotar el 9092 tumbaba también
la API y la consola. El techo era bajo porque la capacidad era baja.

### Por qué no alcanza con cambiar el número

El problema de fondo es que **una IP no es un cliente**. Detrás de la salida de Render, de una
facultad o de cualquier NAT hay muchos equipos, y un mismo equipo puede salir por varias IPs.
Cualquier techo de conexiones abiertas por IP:

- si es bajo, castiga a todos los que comparten salida, y hay que subirlo cada vez que se
  suma uno;
- si es alto, deja de proteger: con la capacidad que había, 1000 por IP equivalía a no tener
  límite, porque una sola máquina llenaba el proxy antes de llegar al tope.

Lo que el límite intentaba resolver son en realidad tres problemas distintos, y cada uno
tiene su herramienta:

| Problema | Dato correcto para resolverlo |
|---|---|
| Alguien sin credenciales satura el puerto | La **frecuencia** con que una IP abre conexiones, no cuántas tiene abiertas |
| Saturar el 9092 tumba la API y la consola | La **capacidad** del proxy |
| Un equipo acapara el broker | La **identidad** del equipo, que conoce el broker y no nginx |

## Opciones consideradas

### 1. Subir `NGINX_KAFKA_CONN_LIMIT`

Una línea en el `.env`. Resuelve lo de Render hoy.

- Sigue midiendo lo equivocado: el valor correcto depende de cuántos equipos compartan IP, y
  ese número cambia sin que nadie avise.
- Con la capacidad de entonces, cualquier valor que alcanzara para varios equipos detrás de
  una IP dejaba a tres IPs llenar el proxy.

### 2. Quitar el límite

- Una sola máquina, sin credenciales, puede ocupar todas las conexiones de nginx y dejar sin
  servicio a Kafka, la API y la consola.

### 3. Terminar el TLS en el broker y mover todos los controles a Kafka

Sacar el bloque `stream` de nginx y que el broker atienda el 9092 con el certificado de Let's
Encrypt. El broker vería la IP real y podría aplicar cuotas por IP y por usuario nativas.

- Es como exponen Kafka los servicios gestionados, y resuelve la limitación "TLS termina en el
  proxy" de [ARCHITECTURE.md](../ARCHITECTURE.md#6-limitaciones-conocidas).
- **La clave privada del certificado del 443 pasaría a estar también en el broker**, un
  proceso más grande y con código propio.
- **El broker quedaría expuesto directamente a internet**, sin nginx absorbiendo el tráfico
  basura, con una imagen fijada a una versión que no recibe parches sola.
- Publicar el puerto del broker hacia afuera abre la posibilidad de un error grave: exponer
  por equivocación el listener interno (29092, sin autenticación) o el controlador (9093).
- La renovación del certificado pasa a necesitar un mecanismo propio de recarga en el broker;
  si falla, a los 90 días ningún equipo puede consumir.

Se descartó por ahora: suma riesgos de seguridad reales, y la parte más valiosa —las cuotas
por equipo— no la necesita (ver la opción 5).

### 4. Que los equipos no hablen con Kafka: una API de consumo

Es lo que hacen las plataformas grandes: Kafka queda interno y los clientes consumen por una
API. Contradice la sección 6 del README, que pide justamente consumir directo, y es construir
un servicio nuevo con cursores. Desproporcionado para ocho equipos.

### 5. Frecuencia en el firewall, capacidad en nginx, identidad en el broker

Cada problema con su dato:

- **Frecuencia:** un límite de conexiones **nuevas** por IP en la cadena `DOCKER-USER` del
  host (`iptables` + `hashlimit`).
- **Capacidad:** nginx con un worker por núcleo y 4096 conexiones cada uno, y el tope por IP
  que queda en nginx pasa a ser un respaldo derivado de esa capacidad.
- **Identidad:** cuotas de Kafka por usuario —el principal es el namespace del token—, con un
  valor por defecto para todos.

## Decisión

**Opción 5.**

**Frecuencia de conexiones nuevas por IP, en el firewall del host**
([firewall-kafka.sh](../../deployment/oracle-single/firewall-kafka.sh)). Una ráfaga de 120
conexiones y 2 por segundo sostenidas por IP. Rechaza con reset y deja un log limitado.

- Un consumidor legítimo abre dos o tres conexiones y las mantiene durante horas. Cien equipos
  detrás de la misma IP casi no abren conexiones nuevas: este límite no depende de cuántos
  compartan la salida.
- La ráfaga se dimensiona con el peor momento legítimo: cada despliegue recrea el proxy y
  todos los consumidores de una IP reconectan a la vez, con reintentos (8 equipos × 3
  conexiones × ~5 intentos ≈ 120). Si alguna vez se supera, las rechazadas reintentan con
  backoff y entran en segundos, cuando el margen se repone. Es una demora durante una
  reconexión masiva, no el corte permanente que había antes.
- Lo que deja pasar a alguien sin credenciales es acotado por construcción: ráfaga más
  frecuencia por el tiempo que nginx espera el handshake (10 s), unas 140 conexiones a medio
  abrir por IP.
- Va en `DOCKER-USER` y no en `INPUT` porque el tráfico hacia un puerto de Docker nunca pasa
  por `INPUT`, y es el lugar que el [ADR-019](ADR-019-firewall-en-la-vcn.md) ya reservaba
  para "filtrado a nivel host para los contenedores". La Security List sigue siendo la que
  decide qué puertos están abiertos: esto no abre ni cierra nada, regula el ritmo de uno ya
  abierto.
- Se persiste con una unidad de systemd atada a Docker, y `deploy.sh` la instala o actualiza
  en cada despliegue.

**Capacidad en nginx** ([nginx.conf.template](../../infrastructure/reverse-proxy/nginx.conf.template)).

- `worker_processes auto` y `worker_connections 4096`, con los descriptores de archivo
  declarados también en el compose (nginx no puede pasar el límite que le da el contenedor).
- `ssl_handshake_timeout 10s` (antes 60 s) y `so_keepalive` en el 9092, para que una conexión
  a medio abrir o de un cliente muerto no retenga un lugar.
- El tope por IP queda en **256, fijo en la plantilla y no en el `.env`**: es un respaldo
  derivado de la capacidad (un octavo de un worker, contando el doble lugar de cada conexión
  al 9092), y sólo cambia si cambia `worker_connections`. Para llegar a él desde una IP hacen
  falta decenas de equipos detrás de ella o alguien que acumule conexiones a propósito. Se
  elimina `NGINX_KAFKA_CONN_LIMIT`.
- Un log de acceso del 9092, que antes no dejaba ningún rastro.

**Identidad en el broker** ([cuotas.sh](../../infrastructure/kafka/cuotas.sh)).

- Por defecto para todos los usuarios: 5 MiB/s de lectura y el 100 % de un hilo del broker
  (de 1100 % totales). Un equipo nuevo las recibe sin configurar nada; uno que necesite más
  recibe una excepción con su nombre.
- **El usuario `ANONYMOUS` —el tráfico interno de la plataforma— queda exceptuado
  explícitamente.** El valor por defecto alcanza a todos los usuarios, incluido el del
  listener sin autenticar: sin la excepción, el gateway, el dispatcher, el Schema Registry y
  kafka-ui quedarían limitados como un equipo más. Medido con Kafka 3.7.1: con un valor por
  defecto de 100 KB/s, un consumidor anónimo tardó 40,8 s en leer 3 MB contra 2,6 s sin
  cuotas; con la excepción, 2,2 s.
- No hay cuota de producción porque los grupos no producen ([ADR-011](ADR-011-autorizacion-derivada-del-token.md)).
- Las cuotas de Kafka no cortan: demoran. Un equipo que se pasa lee más lento, sin errores.

## Consecuencias

### Positivas

- **Lo de Render se resuelve de raíz.** Varios equipos detrás de una IP compartida es un caso
  normal y deja de bloquear a nadie.
- **Ningún valor depende de cuántos clientes haya.** La frecuencia no crece con los equipos
  en régimen, el respaldo de nginx sale de su capacidad, y las cuotas por defecto se aplican
  solas a cada equipo nuevo.
- **Kafka deja de poder tumbar a la API.** El 9092 sigue compartiendo nginx con el 443, pero
  una IP ya no puede acercarse a agotarlo.
- **Aislamiento entre equipos, que antes no había.** Un consumidor con un bug degrada a su
  propio equipo y no a los otros siete.
- **Trazabilidad.** El log del 9092 y el de rechazos del firewall dicen qué IPs se conectan,
  cuánto duran y a cuáles se frenó.
- La clave privada y el broker quedan donde estaban: no se agrega superficie expuesta.

### Negativas

- **Una pieza más fuera de los contenedores.** La regla del firewall vive en el host. Se
  mitiga con la unidad de systemd, la instalación en cada despliegue y el chequeo de
  `preflight.sh`, pero es estado que hay que saber que existe.
- **El entorno local no la tiene.** El firewall es del host de producción; en desarrollo no
  aplica. Su comportamiento se prueba en un namespace de red aislado
  (`tests/integration/limites-de-infraestructura.sh`).
- **La ráfaga sigue siendo un número relacionado con los equipos que comparten IP**, aunque
  sólo durante reconexiones simultáneas y con consecuencias acotadas: demora, no corte.
- **Las cuotas hay que observarlas.** Si el valor por defecto queda bajo para algún equipo
  legítimo, el síntoma es lentitud. Se ve en las métricas de throttling del broker.
- **El TLS sigue terminando en el proxy.** La opción 3 queda disponible si en algún momento el
  broker necesita ver la IP real.

## Cómo verificarlo

En la instancia:

```bash
sudo systemctl status citypass-firewall-kafka            # activa
bash deployment/oracle-single/firewall-kafka.sh estado   # reglas y contadores
sudo journalctl -k | grep 'citypass-kafka rechazo'       # IPs frenadas
docker compose logs reverse-proxy | grep 'status=503'    # cortes por el tope de respaldo
bash infrastructure/kafka/cuotas.sh --ver                # cuotas vigentes y la excepción
```

Los contadores de la cadena `CITYPASS-KAFKA` tienen que crecer con el tráfico real al 9092.
Si quedan en cero mientras hay consumidores conectados, el tráfico no está pasando por
`DOCKER-USER` y la regla no protege nada: es la misma comprobación de contadores que propone
el [ADR-019](ADR-019-firewall-en-la-vcn.md#cómo-verificarlo).

## Referencias

- [ADR-019](ADR-019-firewall-en-la-vcn.md): por qué el filtrado a nivel host para Docker va
  en `DOCKER-USER`
- [ADR-011](ADR-011-autorizacion-derivada-del-token.md): el principal es el namespace, y los
  grupos sólo leen
- [SECURITY.md, Límites](../SECURITY.md#límites)
