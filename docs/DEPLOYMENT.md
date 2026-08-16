# Despliegue en producción

Cómo poner el bus en una VM de la nube, con TLS y sin exponer más de lo necesario.

> Para levantarlo en tu máquina, mirá el [README](../README.md#1-levantarlo-en-tu-máquina).
> Este documento es sólo producción.

---

## Contenido

1. [Qué hace falta antes de empezar](#1-qué-hace-falta-antes-de-empezar)
2. [Preparar la VM](#2-preparar-la-vm)
3. [Configurar el ambiente](#3-configurar-el-ambiente)
4. [Abrir los puertos](#4-abrir-los-puertos)
5. [Emitir el certificado](#5-emitir-el-certificado)
6. [Levantar](#6-levantar)
7. [Verificar](#7-verificar)
8. [Operación](#8-operación)
9. [Integración continua](#9-integración-continua)

---

## 1. Qué hace falta antes de empezar

### Un dominio apuntando a la VM

**No alcanza con la IP.** Let's Encrypt no emite certificados para una IP pelada, y sin un
certificado de una CA pública cada grupo tendría que instalar un truststore propio en su
cliente de Kafka. El que no lo lograra terminaría desactivando la verificación, que es peor
que no tener TLS porque parece seguro.

Un subdominio gratuito alcanza. Creá un registro `A` que apunte a la IP pública de la VM y
esperá a que resuelva:

```bash
dig +short citypass.tudominio.com
```

### El servicio de identidad real

El `auth-simulator` que viene en el repo es un **mock**: credenciales fijas en el código y
clave de firma que se regenera en cada arranque, o sea que cada reinicio invalida todos los
tokens emitidos. Sirve para desarrollar y para la demo, no para producción.

El gateway ya está preparado para un emisor real —valida firma, audiencia y expiración
contra el JWKS— pero mientras el emisor sea el mock, la plataforma entera confía en él. El
contrato que tiene que cumplir el reemplazo está en [AUTH.md](AUTH.md).

### Recursos

Con una VM de 2 vCPU y 4 GB alcanza. El broker y el gateway son los que más consumen.

Los techos de recursos —memoria por contenedor, rotación de logs, retención de Kafka por
tamaño y los límites de la API— se declaran en el `.env` de cada ambiente, no en el
compose: dependen de la máquina que hospede el sistema, y el compose no sabe dónde va a
correr. Están agrupados al final de `.env.dev` y `.env.prod`, con el porqué de cada valor.

Tres de ellos deciden si un disco lleno es posible:

| Variable | Qué acota |
|---|---|
| `LOG_MAX_SIZE` × `LOG_MAX_FILES` | Lo que puede escribir cada contenedor en su log |
| `KAFKA_RETENTION_BYTES` × `KAFKA_SEGMENT_BYTES` | El tamaño máximo de cada tópico |
| `RATE_LIMIT_PER_MINUTE` × `MAX_PAYLOAD_BYTES` | La velocidad a la que un equipo puede escribir |

El del log es el que más se olvida: el driver `json-file` de Docker no tiene límite por
defecto, así que sin esa variable un servicio que loguee mucho llena el disco sin pasar
por ningún otro control.

Y hay una alerta de disco provisionada en Grafana, en la carpeta **CityPass**: dispara
cuando el uso pasa del 80 % y se mantiene diez minutos así.

El dato sale de `disk_free_bytes`, que el actuator del gateway ya expone y Prometheus ya
scrapea, así que no hizo falta agregar `node_exporter` ni montar el socket de Docker —que
es justamente lo que se evita para que un contenedor comprometido no pueda salirse. La
métrica mide el sistema de archivos donde corre el contenedor, o sea el disco del host.

El umbral es un porcentaje y no una cantidad de bytes a propósito: 80 % significa lo mismo
en un disco de 200 GB que en uno de 2 TB, así que no depende del ambiente y no necesita
estar en el `.env`.

> **La alerta detecta, pero por ahora no avisa a nadie.** Grafana trae un contact point de
> correo sin servidor SMTP configurado, así que la alerta se ve en su interfaz y nada más.
> Para que llegue un mail hay que definir las variables `GF_SMTP_*` del contenedor de
> Grafana con un servidor real.

Y en el de Kafka hay dos trampas que conviene conocer:

**La retención es por partición, no por broker.** Kafka no tiene un techo global: hay una
partición por event type, así que el total es `KAFKA_RETENTION_BYTES` × la cantidad de
event types. Con 5 MB, diez event types ocupan 50 MB y cien ocupan 500 MB.

**El segmento tiene que ser más chico que la retención.** Kafka borra segmentos enteros,
nunca eventos sueltos. Con el default de 1 GB, el segmento activo crece hasta 1 GB antes
de rotar, así que una retención de 5 MB no podría borrar nada y el techo sería decorativo.
Por eso `KAFKA_SEGMENT_BYTES` existe como variable y vale bastante menos que la retención.

---

## 2. Preparar la VM

```bash
sudo apt update && sudo apt install -y docker.io docker-compose-v2
sudo usermod -aG docker $USER
newgrp docker

git clone <url-del-repo>
cd citypass-eda
```

---

## 3. Configurar el ambiente

```bash
cp .env.prod .env
nano .env
```

`.env.prod` ya trae los valores correctos y explica cada variable. Sólo hay que reemplazar
el dominio, el mail y la contraseña de kafka-ui:

| Variable | Valor | Qué produce |
|---|---|---|
| `PUBLISH_ADDR` | `127.0.0.1` | Ningún servicio queda accesible desde internet salvo por el proxy |
| `KAFKA_UI_PUBLISH_ADDR` | `127.0.0.1` | kafka-ui sólo por túnel SSH |
| `KAFKA_HOST_PORT` | `19092` | El 9092 pasa a ser del proxy, que termina TLS |
| `COMPOSE_PROFILES` | `prod` | Levanta `reverse-proxy` y `certbot` |
| `PUBLIC_DOMAIN` | tu dominio | Nombre del certificado |
| `CERTBOT_EMAIL` | tu mail | Avisos de vencimiento de Let's Encrypt |
| `KAFKA_ADVERTISED_HOST` | tu dominio | Tiene que coincidir con el certificado |
| `SPRING_PROFILES_ACTIVE` | vacío | Deja los valores de producción del gateway |
| `KAFKA_UI_USER` / `KAFKA_UI_PASSWORD` | los tuyos | **No dejar `CAMBIAR`** |
| `GRAFANA_USER` / `GRAFANA_PASSWORD` | los tuyos | Ídem: el compose no arranca si faltan |
| `AUTH_CORS_ORIGIN` / `GATEWAY_CORS_ORIGIN` | `https://tu-dominio` | Un solo origen, nunca `*` |
| `LOGIN_API_URL` / `GATEWAY_API_URL` | `https://tu-dominio/...` | Las lee la UI al arrancar, no en el build |

**Si alguna variable falta, el compose usa el valor de producción igual**: los defaults son
los seguros. Olvidarse nunca abre nada.

La única excepción son las credenciales de kafka-ui: si faltan, el compose se niega a
arrancar, porque para una contraseña no existe un default seguro.

> `.env.prod` **se versiona** —es una plantilla— así que la contraseña real va sólo en el
> `.env` de la VM, que está en `.gitignore`.

---

## 4. Abrir los puertos

En la consola del proveedor —el grupo de seguridad, security list o firewall de red, según
cómo lo llame cada uno— sólo estas tres reglas de entrada:

| Puerto | Protocolo | Para qué |
|---|---|---|
| 80 | TCP | Desafío de ACME y redirección a HTTPS |
| 443 | TCP | API, UI y servicio de identidad |
| 9092 | TCP | Kafka sobre TLS |

Y en la VM:

```bash
sudo iptables -I INPUT -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 443 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 9092 -j ACCEPT
```

Los puertos 8080, 8081, 8083, 8084, 8090, 9090, 9091 y 3000 **no se abren**. El Schema Registry y
kafka-ui no tienen autenticación propia: expuestos, cualquiera podría borrar subjects o
administrar el cluster. Quedan alcanzables sólo desde la VM por `127.0.0.1`.

---

## 5. Emitir el certificado

Hay un orden obligatorio: nginx no arranca sin certificado, y certbot necesita el puerto 80,
que en régimen normal usa nginx. Por eso la primera emisión se hace **antes** de levantar el
stack:

```bash
source .env
docker compose run --rm -p 80:80 certbot certonly --standalone \
  --cert-name citypass -d "$PUBLIC_DOMAIN" \
  --email "$CERTBOT_EMAIL" --agree-tos --no-eff-email -n
```

El nombre `citypass` es fijo a propósito: la configuración de nginx referencia
`/etc/letsencrypt/live/citypass/`, así que no depende del dominio y no necesita plantillas
ni sustitución de variables.

A partir de acá el servicio `certbot` renueva solo cada 12 horas —Let's Encrypt sólo renueva
cuando faltan menos de 30 días— y nginx se recarga cada 6 horas para tomar el certificado
nuevo.

---

## 6. Levantar

```bash
docker compose pull
docker compose up -d --no-build
```

**En producción no se construye.** Las imágenes llegan ya compiladas desde el registro, y
`--no-build` es lo que lo garantiza: si faltara alguna, esto falla en vez de ponerse a
compilarla en la instancia. Compilar acá dejaría la aplicación degradada mientras dura el
build, llenaría el disco de caché y, si fallara, te dejaría sin ambiente.

Qué versión se levanta lo decide `TAG` en el `.env`. Lo escribe el despliegue automático
(ver §9), pero se puede fijar a mano para volver a una versión anterior.

---

## 7. Verificar

```bash
docker compose ps
```

Tienen que figurar nueve servicios: los siete de siempre más `reverse-proxy` y `certbot`. Si
el proxy no aparece, lo primero a revisar es que `COMPOSE_PROFILES` diga exactamente `prod`
— Compose ignora en silencio un perfil que no existe.

```bash
curl https://citypass.tudominio.com/health
```

Y que nada más esté expuesto:

```bash
# desde afuera de la VM, tienen que fallar todos
for p in 8080 8081 8083 8084 8090 9090; do
  nc -z -w2 citypass.tudominio.com $p && echo "PUERTO $p ABIERTO — revisar" || echo "$p cerrado"
done
```

### URLs públicas

Todo entra por el proxy; no hay un puerto por servicio.

| Servicio | URL |
|---|---|
| UI | `https://citypass.tudominio.com/` |
| API del gateway | `https://citypass.tudominio.com/api/v1/...` |
| Servicio de identidad | `https://citypass.tudominio.com/auth/oauth/token` |
| Kafka broker | `citypass.tudominio.com:9092` con `security.protocol=SASL_SSL` |

Swagger (`/doc`), el Schema Registry, kafka-ui, Prometheus y Grafana **no se publican**.

---

## 8. Operación

### Acceso administrativo

Por túnel SSH, sin abrir ningún puerto:

```bash
ssh -L 8081:127.0.0.1:8081 -L 8090:127.0.0.1:8090 \
    -L 9090:127.0.0.1:9090 -L 9091:127.0.0.1:9091 -L 3000:127.0.0.1:3000 \
    usuario@citypass.tudominio.com
```

Y se navegan como `http://localhost:8081` (registry), `http://localhost:8090` (kafka-ui),
`http://localhost:9091` (Prometheus) y `http://localhost:3000` (Grafana). kafka-ui y Grafana
piden además su propio usuario y contraseña.

Como el túnel SSH pasa a ser la puerta de todo el acceso administrativo, conviene confirmar
que el servidor esté con clave y no con contraseña — `PasswordAuthentication no` en
`/etc/ssh/sshd_config`.

### Logs

```bash
docker compose logs -f event-gateway
docker compose logs -f reverse-proxy
```

En producción el gateway loguea en `INFO`. `DEBUG` vuelca los payloads de los eventos al
log, o sea datos de negocio de todos los grupos.

### Actualizar

Normalmente no se hace a mano: un merge a `main` despliega solo (§9). Para forzar una
versión concreta:

```bash
sed -i 's|^TAG=.*|TAG=<sha del commit>|' .env
docker compose pull && docker compose up -d --no-build
```

Ese mismo procedimiento es el **rollback**: se apunta `TAG` al SHA anterior. Como cada
despliegue etiqueta las imágenes con el SHA del commit, volver atrás es determinístico y
no depende de reconstruir nada.

Las suscripciones y los schemas viven en volúmenes, así que sobreviven. Los tópicos
también.

### Backup

El estado que importa está en tres volúmenes:

```bash
docker run --rm -v citypass-eda_kafka-data:/d -v $PWD:/b alpine tar czf /b/kafka-data.tgz -C /d .
docker run --rm -v citypass-eda_event-gateway-data:/d -v $PWD:/b alpine tar czf /b/gateway-data.tgz -C /d .
docker run --rm -v citypass-eda_event-gateway-schemas:/d -v $PWD:/b alpine tar czf /b/schemas.tgz -C /d .
```

Perder `event-gateway-data` borra las suscripciones webhook, que hoy no están replicadas en
ningún lado. Es la limitación descrita en [ARCHITECTURE.md](ARCHITECTURE.md#6-limitaciones-conocidas).

---

---

## 9. Integración continua

`.github/workflows/deploy.yml` verifica cada cambio y publica las imágenes. **No despliega
en ningún ambiente**: deja las imágenes listas en `ghcr.io`, etiquetadas con el SHA del
commit.

| Job | Qué hace |
|---|---|
| `gateway` | Tests de `event-gateway`, con su umbral de cobertura |
| `authorizer` | Tests de `kafka-authorizer` |
| `ui` | Build y lint del frontend |
| `configuracion` | Paridad de `.env.dev` y `.env.prod` |
| `construir` | Las cinco imágenes en paralelo, etiquetadas con el SHA |
| `etiquetar` | Mueve `latest` al commit ya verificado |

Los cuatro primeros y `construir` **arrancan a la vez**. Los PR ejecutan sólo la
verificación: nada se publica hasta que el cambio está en `main`.

### Por qué construir no espera a los tests

Construir una imagen que después falle los tests no cuesta nada mientras no llegue a
usarse, y así la verificación sale del camino crítico. Medido sobre este repositorio, la
verificación tarda ~56 s en serie y la construcción unos dos minutos: solapadas, el
pipeline pasa de unos cuatro minutos a unos dos y medio.

Lo que sí importa es que una imagen sin verificar no quede como «la última». Por eso
`construir` publica **sólo la etiqueta del SHA**, y `latest` se mueve en un job aparte que
sí espera a los tests. Ese job usa `docker buildx imagetools create`, que copia el
manifiesto del lado del servidor: no descarga ni vuelve a subir nada.

Como efecto lateral, Gradle compila el gateway dos veces —una en los tests y otra dentro
del build de la imagen— pero al correr en paralelo eso ya no cuesta tiempo de reloj.
Evitarlo exigiría que el `Dockerfile` recibiera un jar ya compilado, y entonces
`docker compose build` dejaría de funcionar en una máquina de desarrollo: dos caminos de
build que se pueden desincronizar, a cambio de nada.

### Runners ARM

Los jobs que compilan corren en `ubuntu-24.04-arm`. Si el destino es una máquina ARM,
compilar ahí es nativo y evita emular con QEMU, que tarda varias veces más. Son gratuitos
en repositorios públicos.

### Desplegar en un ambiente concreto

Este workflow deja las imágenes publicadas; llevarlas a una máquina es responsabilidad de
cada ambiente, **en la rama de ese ambiente**. La razón es práctica: si el despliegue
viviera acá, cambiar de proveedor obligaría a tocar la rama principal, y cada ambiente
nuevo entraría en conflicto con los demás.

Una rama de ambiente agrega sus propios archivos —su workflow y su script— y no modifica
ninguno de los que ya existen, así que los merges desde `main` nunca dan conflicto.

---

## Problemas frecuentes

| Síntoma | Causa probable |
|---|---|
| El proxy no aparece en `docker compose ps` | `COMPOSE_PROFILES` no dice exactamente `prod` |
| nginx en bucle de reinicios quejándose de los upstreams | Conflicto de puertos: el contenedor quedó sin red. Revisar que `KAFKA_HOST_PORT` no sea 9092 |
| nginx no arranca por certificado faltante | Falta la emisión inicial del paso 5 |
| Los clientes de Kafka no conectan | `KAFKA_ADVERTISED_HOST` tiene que ser el dominio, y coincidir con el certificado |
| El navegador bloquea las llamadas de la UI | `GATEWAY_CORS_ORIGIN` tiene que ser el origen exacto, con `https://` |
| Los tokens dejan de servir tras un reinicio | El `auth-simulator` regenera su clave. Es el mock: hace falta el servicio real |

---

## Referencias

- [SECURITY.md](SECURITY.md) — por qué está expuesto sólo lo que está expuesto
- [ADR-014](adr/ADR-014-un-compose-configuracion-en-env.md) — un solo compose, la configuración en el `.env`
- [diagrams/despliegue.md](diagrams/despliegue.md) — diagrama de despliegue
