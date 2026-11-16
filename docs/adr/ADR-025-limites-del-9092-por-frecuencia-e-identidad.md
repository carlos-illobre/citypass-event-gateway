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

- **Cuenta handshakes TCP completos, no SYN.** La primera versión contaba SYN, y una revisión
  de seguridad mostró que así la regla se podía usar contra un tercero: un SYN se manda con
  la IP de origen falsificada sin necesidad de recibir respuesta, y 40 SYN con la IP de otro
  le vaciaban el margen, sin que el atacante abriera una sola conexión. La víctima natural
  era la IP compartida de Render, el caso que motivó todo esto. Contando el ACK que completa
  el handshake (segundo paquete del cliente, en su dirección, con la conexión ya
  establecida), hace falta haber recibido el SYN-ACK del servidor, y eso no se puede desde
  una IP falsificada. Probado: 60 SYN falsificados no le quitan nada a la víctima. Como la
  tabla de `hashlimit` sólo registra IPs que completaron un handshake, tampoco se la puede
  llenar con orígenes inventados.
- **Sólo cuenta conexiones que entraron por el puerto publicado** (`--ctstate DNAT`). El
  salto de nginx al broker también va a un 9092 y también pasa por `DOCKER-USER`, porque
  Docker hace que el tráfico entre contenedores recorra `FORWARD`. Sin esta condición se
  contaba como un cliente más con la IP de nginx: todos los equipos compartían un único
  margen y, pasadas 120 conexiones nuevas en total, se cortaba a todos. Las pruebas aisladas
  no podían verlo; lo encontró la prueba de punta a punta con Docker real
  ([proteccion-9092.sh](../../tests/e2e/proteccion-9092.sh)), que corre en la CI.

- Un consumidor legítimo abre dos o tres conexiones y las mantiene durante horas. Cien equipos
  detrás de la misma IP casi no abren conexiones nuevas: este límite no depende de cuántos
  compartan la salida.
- La ráfaga se dimensiona con el peor momento legítimo: cada despliegue recrea el proxy y
  todos los consumidores de una IP reconectan a la vez, con reintentos (8 equipos × 3
  conexiones × ~5 intentos ≈ 120). Si alguna vez se supera, las rechazadas reintentan con
  backoff y entran en segundos, cuando el margen se repone. Es una demora durante una
  reconexión masiva, no el corte permanente que había antes.
- Frena la **velocidad** con que una IP acumula conexiones, no cuántas termina teniendo:
  alguien sin credenciales puede completar el TLS y quedarse callado hasta que el broker lo
  corta, a los 10 minutos. Lo que acota cuántas acumula es el tope de respaldo de nginx.
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
- El tope por IP queda en **64, fijo en la plantilla y no en el `.env`**, como respaldo.
  Ningún tope de conexiones abiertas por IP es bueno en las dos direcciones: alto, alcanza con
  pocas IPs para llenar nginx; bajo, castiga a los que comparten salida. 64 se eligió mirando
  las dos: son 128 lugares contando el salto al broker, 1/32 de un worker, así que para
  llenar nginx hacen falta unas 64 IPs acumulando conexiones a la vez (con el tope viejo de
  10 sobre un worker de 1024 bastaban unas 51); y entran unos 20 equipos detrás de una misma
  IP, el doble de los que tiene el curso. Una primera versión usaba 256, que con la capacidad
  nueva bajaba a 16 las IPs necesarias para llenar nginx: era peor que el límite viejo frente
  a un atacante con varias IPs. Se elimina `NGINX_KAFKA_CONN_LIMIT`.
- `mem_limit` para el reverse-proxy (`MEM_LIMIT_PROXY`, 512 MB). Era el único servicio sin
  techo de memoria, y la capacidad nueva lo multiplica por ocho: a unos 80 KB por conexión
  TLS al 9092, el peor caso ronda los 300 MB. Con techo, un pico reinicia el proxy en vez de
  presionar la memoria de toda la máquina.
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
- **Tumbar la API desde el 9092 se vuelve mucho más difícil, pero no imposible.** Los dos
  siguen compartiendo nginx: hacen falta unas 64 IPs acumulando conexiones a la vez. El
  aislamiento completo sería un nginx aparte para Kafka.
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
- **Las cuotas hay que observarlas, y hoy no se ven.** Si el valor por defecto queda bajo para
  algún equipo legítimo, el síntoma es lentitud. El broker expone el throttling por JMX, pero
  Prometheus no recoge métricas del broker: hasta que lo haga, sólo se ve consultando JMX a
  mano o porque un equipo avisa.
- **La excepción de cuotas se apoya en el nombre `ANONYMOUS`**, igual que el autorizador del
  [ADR-011](ADR-011-autorizacion-derivada-del-token.md), que le da acceso total a ese
  principal sin mirar por qué listener entró. Como el principal externo sale del claim
  `namespace`, un token con `namespace: ANONYMOUS` tendría control total del bus y además
  quedaría sin cuotas. Hace falta que el servicio de identidad emita ese token, pero la
  defensa correcta es decidir por el listener interno y no por el nombre. Queda pendiente.
- **El ataque desde varias IPs sigue siendo posible.** Un atacante sin credenciales puede
  completar el TLS y quedarse callado hasta que el broker lo corta, a los 10 minutos; con unas
  64 IPs llena nginx. Acortar ese tiempo para las conexiones que no se autentican es la
  mejora siguiente; no se verificó todavía qué permite Kafka para eso.
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

## Cómo volver atrás

Desplegar un commit anterior **no** deshace estas piezas: la regla del firewall, su unidad de
systemd y las cuotas viven fuera de los contenedores y sobreviven al despliegue. No molestan
—una versión anterior con ellas activas funciona igual—, pero para quitarlas:

```bash
sudo systemctl disable --now citypass-firewall-kafka      # ExecStop saca las reglas
sudo rm /etc/systemd/system/citypass-firewall-kafka.service /usr/local/sbin/citypass-firewall-kafka
sudo systemctl daemon-reload
bash infrastructure/kafka/cuotas.sh --quitar
```

**No borres `NGINX_KAFKA_CONN_LIMIT` del `.env` de la instancia** mientras puedas necesitar
volver a una versión anterior a esta: nada la lee desde acá en adelante, pero el compose
viejo la exige y sin ella no arranca.

## Referencias

- [ADR-019](ADR-019-firewall-en-la-vcn.md): por qué el filtrado a nivel host para Docker va
  en `DOCKER-USER`
- [ADR-011](ADR-011-autorizacion-derivada-del-token.md): el principal es el namespace, y los
  grupos sólo leen
- [SECURITY.md, Límites](../SECURITY.md#límites)
