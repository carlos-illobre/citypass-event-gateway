# ADR-019: El firewall vive en la VCN, no en el host

**Estado:** Aceptado  
**Fecha:** 2026-08-19

---

## Contexto

La plataforma corre en una instancia con once contenedores. Sólo uno —el reverse-proxy—
escucha en todas las interfaces; el resto publica sobre `127.0.0.1` mediante `PUBLISH_ADDR`.
Hay tres puertos abiertos hacia internet: 80, 443 y 9092.

La pregunta es dónde se aplica el control de acceso a la red, y la respuesta importa porque
**la intuición apunta al lugar equivocado**.

### El hecho que ordena la decisión

Cuando un contenedor publica un puerto, Docker inserta un DNAT en `nat/PREROUTING`. A partir
de ahí el destino del paquete es la IP del contenedor, así que el kernel lo **enruta** en
lugar de entregarlo localmente: recorre `FORWARD` → `DOCKER-USER` → `DOCKER`, y **nunca pasa
por `INPUT`**.

No es una hipótesis. Medido en la instancia en producción, con el sistema sirviendo tráfico:

| Puerto | Regla en `INPUT` | Cadena `DOCKER` |
|---|---:|---:|
| 443 | 31 paquetes | **13.426** |
| 80 | 3 | **10.008** |
| 9092 | 0 | **743** |

Por el 443 pasaron **433 veces más paquetes por `DOCKER` que por `INPUT`**. Los 31 que sí
llegaron a `INPUT` tienen explicación: entraron mientras el contenedor del proxy estaba
detenido, y sin contenedor no existe el DNAT que los desvía.

La consecuencia es que **cualquier firewall que actúe sobre `INPUT` es irrelevante para los
puertos de los contenedores**, aunque sus reglas se listen y parezcan correctas. Es el mismo
tipo de engaño que la trampa del `REJECT`, pero un nivel más abajo: ahí la regla estaba en
la cadena correcta y en el lugar equivocado; acá está en la cadena equivocada.

## Opciones consideradas

### 1. `iptables` en la cadena `INPUT`

Es lo que sugiere la intuición y lo que hace casi toda la documentación genérica de
endurecimiento de servidores.

- Para los puertos publicados por Docker, **no filtra nada**: el tráfico no pasa por ahí.
- Para lo que escuche directamente en el host, sí filtra. Hoy no hay nada, pero podría
  haberlo.
- Es la última línea de los servicios internos: como su DNAT sólo matchea `127.0.0.1`, un
  paquete dirigido a la IP pública no matchea, sigue a `INPUT` y ahí lo corta el `REJECT`.

### 2. `ufw` o `firewalld`

Envoltorios sobre lo mismo. Heredan el problema completo de la opción 1 y le agregan uno
peor: **presentan una interfaz que sugiere que el puerto está cerrado cuando no lo está.**
Un `ufw status` que dice `443/tcp DENY` junto a un servicio que responde en el 443 es la
peor combinación posible, porque desalienta seguir investigando.

### 3. La cadena `DOCKER-USER`

Es el punto de filtrado a nivel host que Docker sí respeta: la recorre antes de sus propias
reglas y **sobrevive a un `docker compose up`**, a diferencia de cualquier regla insertada
en la cadena `DOCKER`, que Docker reescribe.

- Filtra de verdad el tráfico hacia los contenedores.
- Es la opción correcta si hace falta filtrado a nivel host.
- Requiere persistirla aparte y es un mecanismo que hay que conocer para no romperlo.

### 4. La Security List de la VCN

El filtro de la red virtual, aplicado **fuera de la máquina**.

- Ningún DNAT ni configuración del host la esquiva: el paquete no llega.
- Se administra en la consola, es declarativa y no depende de que algo se persista bien.
- No distingue entre procesos del host y contenedores, porque decide antes de que eso exista.
- No cubre el tráfico que se origina dentro de la propia instancia.

## Decisión

**El control de acceso a la red se aplica en la Security List de la VCN.** Es la única capa
que filtra de forma efectiva los puertos publicados por Docker, y es la que define qué está
abierto: 22, 80, 443 y 9092.

**Las reglas de `iptables` en `INPUT` se mantienen**, pero por lo que efectivamente hacen y
no como mecanismo principal: cubren un eventual servicio que escuche en el host, y actúan
como última línea de los servicios internos cuyo DNAT no matchea el tráfico externo.

**No se instala `ufw` ni `firewalld`.**

**Si en algún momento hace falta filtrado a nivel host para los contenedores, va en
`DOCKER-USER`.** Hoy no hace falta: sólo el proxy publica hacia afuera, y lo que decide
quién entra es la Security List.

La defensa más fuerte, de todos modos, no es ninguna de las cuatro: es que **diez de los
once contenedores no escuchan fuera de loopback**. Un puerto que no existe no hay que
filtrarlo. El firewall protege la superficie que queda, no la que se decidió no crear.

## Consecuencias

### Positivas

- **Una sola capa manda, y es explícita.** Qué está abierto se lee en un solo lugar, en la
  consola, sin componer el estado de dos mecanismos que interactúan de forma no obvia.
- **No depende de la persistencia del host.** Un `netfilter-persistent save` olvidado no
  abre nada: la Security List sobrevive a reinicios, reimágenes y cambios de kernel.
- **Se elimina una falsa sensación de seguridad.** Documentar que `INPUT` no filtra los
  contenedores evita la decisión de abrir algo en la consola confiando en que `iptables` lo
  va a frenar.

### Negativas

- **La configuración vive fuera del repositorio.** Es estado en la consola de Oracle, no un
  archivo versionado, así que no hay historial ni revisión por PR. Se compensa
  documentándola en [ORACLE.md](../../deployment/oracle-single/ORACLE.md).
- **Es específica del proveedor.** Migrar implica reconstruirla en el equivalente del otro
  —un Security Group en AWS—, aunque el concepto se traslada directo.
- **No protege dentro de la instancia.** Un proceso comprometido en la propia máquina llega
  a todos los servicios internos sin cruzar la Security List. Lo que acota ese caso es que
  los contenedores no comparten red con el host y que el listener interno de Kafka sólo es
  alcanzable dentro de la red de Docker.
- **Las reglas de `iptables` quedan sin ser lo que aparentan.** Se mantienen por buenas
  razones, pero alguien que las lea sin este ADR va a suponer que son el control principal.
  Por eso el aviso está también en [ORACLE.md §7](../../deployment/oracle-single/ORACLE.md).

## Cómo verificarlo

La afirmación de este ADR es medible, y conviene rehacer la medición si alguien cambia la
topología de red:

```bash
sudo iptables -L INPUT -n -v --line-numbers    # los contadores de 80/443/9092
sudo iptables -L DOCKER -n -v                  # los mismos puertos, hacia los contenedores
sudo iptables -t nat -L DOCKER -n              # qué destino matchea cada DNAT
```

Si los contadores de `INPUT` son órdenes de magnitud menores que los de `DOCKER`, la
decisión sigue siendo la correcta. Y en la tercera salida, los servicios internos tienen que
mostrar `127.0.0.1` como destino: **si alguno muestra `0.0.0.0/0`, quedó publicado hacia
afuera** y la Security List es lo único que lo está tapando.

## Referencias

- [SECURITY.md](../SECURITY.md#6-superficie-expuesta) — la superficie expuesta y las dos
  capas que la protegen
- [ORACLE.md §7](../../deployment/oracle-single/ORACLE.md) — cómo se abren los puertos y la
  trampa del `REJECT`
- [ADR-011](ADR-011-autorizacion-derivada-del-token.md) — la autorización del bus, que es lo
  que protege el 9092 una vez que el paquete entra
- [ADR-014](ADR-014-un-compose-configuracion-en-env.md) — `PUBLISH_ADDR`, que es lo que hace
  que diez de los once contenedores no necesiten firewall
