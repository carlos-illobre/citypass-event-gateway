# Desinstalar CityPass+ EDA de Oracle Cloud

Cómo dejar la instancia como estaba, deshaciendo lo que hizo
[ORACLE.md](ORACLE.md) en orden inverso.

Está organizado en **niveles**: cada uno borra más que el anterior. Podés parar en cualquiera.
Sólo los dos primeros son reversibles sin volver a instalar.

Todos los comandos se corren **desde tu máquina**, con los valores de `oracle/.env`:

```bash
set -a; . oracle/.env; set +a
```

---

## Contenido

1. [Antes de borrar nada](#1-antes-de-borrar-nada)
2. [Nivel 1 · Apagar la aplicación](#nivel-1--apagar-la-aplicación)
3. [Nivel 2 · Borrar los datos](#nivel-2--borrar-los-datos)
4. [Nivel 3 · Borrar el proyecto de la instancia](#nivel-3--borrar-el-proyecto-de-la-instancia)
5. [Nivel 4 · Cerrar los puertos](#nivel-4--cerrar-los-puertos)
6. [Nivel 5 · Desinstalar Docker](#nivel-5--desinstalar-docker)
7. [Lo que vive fuera de la instancia](#lo-que-vive-fuera-de-la-instancia)
8. [Comprobar que quedó limpia](#comprobar-que-quedó-limpia)
9. [Terminar la instancia](#terminar-la-instancia)

---

## 1. Antes de borrar nada

### Qué se pierde

A partir del nivel 2 no hay vuelta atrás. Lo que desaparece:

| Volumen | Qué contiene |
|---|---|
| `kafka-data` | **Todos los eventos publicados por todos los grupos** |
| `event-gateway-schemas` | Los contratos de los event types |
| `event-gateway-data` | Las suscripciones a webhooks |
| `certbot-conf` | El certificado y su clave privada |
| `grafana-data` | Dashboards y su configuración |
| `prometheus-data` | La serie histórica de métricas |

Los event types y los eventos **no se pueden recuperar** de ningún lado: no están en el
repositorio ni en las imágenes. Si otros equipos publicaron cosas ahí, avisales antes.

### Sacar una copia, por si acaso

Si querés poder volver, esto guarda los tres volúmenes que tienen datos irreemplazables:

```bash
ssh $SSH "cd $CITYPASS_DIR && for v in kafka-data event-gateway-schemas event-gateway-data; do docker run --rm -v citypass-event-gateway_\$v:/d -v \$PWD:/backup alpine tar czf /backup/\$v.tgz -C /d .; done && ls -lh *.tgz"
```

Y traértelos:

```bash
scp "$SSH:$CITYPASS_DIR/*.tgz" .
```

> El certificado **no hace falta respaldarlo**. Si algún día reinstalás, certbot emite uno
> nuevo gratis; guardar una clave privada que ya no se usa es sumar un riesgo sin ganancia.

---

## Nivel 1 · Apagar la aplicación

Detiene todo sin borrar nada. **Reversible**: se vuelve con `docker compose up -d --no-build`.

```bash
ssh $SSH "cd $CITYPASS_DIR && docker compose down"
```

Sirve para liberar CPU y memoria mientras decidís, o para dejar la instancia en pausa sin
perder el estado. Los volúmenes quedan intactos.

---

## Nivel 2 · Borrar los datos

**Irreversible.** El `-v` es lo que borra los volúmenes de la tabla de arriba.

```bash
ssh $SSH "cd $CITYPASS_DIR && docker compose down -v --remove-orphans"
```

Y las imágenes, que son varios GB:

```bash
ssh $SSH "docker image prune -a -f && docker system df"
```

`docker system df` te muestra cuánto quedó. Es normal que informe algo de caché de build
aunque nunca hayas compilado acá.

> Si el `down -v` se queja de que un volumen está en uso, hay un contenedor de otro proyecto
> usándolo. `docker ps -a` para ver cuál.

---

## Nivel 3 · Borrar el proyecto de la instancia

```bash
ssh $SSH "rm -rf $CITYPASS_DIR"
```

Ahí se va el checkout y, con él, **el `.env` con las contraseñas de Grafana y kafka-ui**. Si
las querés conservar, copialas antes:

```bash
ssh $SSH "grep -E '^(KAFKA_UI|GRAFANA)_(USER|PASSWORD)=' $CITYPASS_DIR/.env"
```

---

## Nivel 4 · Cerrar los puertos

Dos lugares, como al abrirlos.

### En la instancia

```bash
ssh $SSH 'sudo iptables -L INPUT -n --line-numbers'
```

Borrá las tres reglas por **contenido y no por número**, que es más seguro: los números se
recalculan después de cada borrado y es fácil eliminar el `REJECT` por error.

```bash
ssh $SSH 'sudo iptables -D INPUT -p tcp -m state --state NEW --dport 80 -j ACCEPT; sudo iptables -D INPUT -p tcp -m state --state NEW --dport 443 -j ACCEPT; sudo iptables -D INPUT -p tcp -m state --state NEW --dport 9092 -j ACCEPT; sudo netfilter-persistent save; sudo iptables -L INPUT -n --line-numbers'
```

Tienen que quedar cinco reglas y la última el `REJECT`. Sin el `netfilter-persistent save`,
las reglas borradas vuelven al reiniciar.

### En la consola de Oracle

*Compute → Instances → tu instancia → **Subnet** → pestaña **Security** → la security list →
Ingress Rules*, y borrás las reglas de **80, 443 y 9092**.

> **Fijate en la columna de descripción antes de borrar.** Si el 80 y el 443 ya existían de
> un despliegue anterior —es habitual, y se nota porque la descripción menciona otra cosa—,
> borralos sólo si estás seguro de que nada más los usa. El 9092 sí es de este proyecto.

**Nunca borres la regla del 22**: es tu SSH y te quedás afuera de la instancia.

---

## Nivel 5 · Desinstalar Docker

Sólo si querés la máquina realmente vacía. Si vas a usarla para otra cosa, dejalo.

```bash
ssh $SSH 'sudo apt-get purge -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin docker.io docker-compose-v2 docker-buildx 2>/dev/null; sudo apt-get autoremove -y'
```

`purge` **no borra `/var/lib/docker`**, así que las imágenes y volúmenes que hubieran quedado
siguen ocupando disco. Para llevárselos también:

```bash
ssh $SSH 'sudo rm -rf /var/lib/docker /var/lib/containerd /etc/docker && df -h /'
```

### Lo que conviene NO revertir

Durante la instalación se desactivó `rpcbind`, que escuchaba en el puerto 111 sin que nada lo
usara. **Dejalo desactivado**: es superficie de ataque que no aporta nada, tengas o no esta
aplicación. Si alguna vez necesitás NFSv3, se revierte con
`sudo systemctl enable --now rpcbind.socket`.

---

## Lo que vive fuera de la instancia

### El dominio

En [duckdns.org](https://www.duckdns.org), **delete domain**. Mientras siga apuntando a una IP
que ya no atiende, cualquiera que abra ese nombre ve un error de conexión.

Si en cambio pensás reusarlo, dejalo y sólo repuntá la IP cuando haga falta.

### El certificado

No hace falta hacer nada: Let's Encrypt caduca solo a los 90 días. Revocarlo tiene sentido en
un caso concreto —que sospeches que la clave privada se filtró—, y en ese caso se hace **antes**
de borrar el volumen `certbot-conf`, porque revocar necesita la clave:

```bash
ssh $SSH "cd $CITYPASS_DIR && docker compose run --rm --entrypoint certbot certbot revoke --cert-name citypass --non-interactive"
```

### Las imágenes del registro

Las de `ghcr.io` pertenecen al repositorio, no a la instancia: no se borran al desinstalar, y
no cuestan nada. Si igual las querés sacar, es desde
*github.com/TU_USUARIO?tab=packages* → cada paquete → *Package settings* → *Delete*.

### En tu máquina

El archivo con tus datos:

```bash
rm oracle/.env
```

Y la entrada de `~/.ssh/config`. Primero mirá qué hay:

```bash
grep -n -A4 "^Host $SSH\$" ~/.ssh/config
```

Lo más seguro es borrar esas líneas a mano con un editor. Si preferís un comando, este saca
sólo el bloque —desde su `Host` hasta el siguiente, o hasta el final— y deja el resto:

```bash
awk -v h="Host $SSH" '/^Host /{omitir = ($0 == h)} !omitir' ~/.ssh/config > /tmp/sshconfig.nuevo && diff ~/.ssh/config /tmp/sshconfig.nuevo
```

El `diff` te muestra exactamente qué se va a perder. Si es sólo tu bloque, confirmás:

```bash
mv /tmp/sshconfig.nuevo ~/.ssh/config && chmod 600 ~/.ssh/config
```

> No uses un `sed` de rango del tipo `/^Host x$/,/^$/d`. Si tu entrada no termina en una línea
> en blanco —y las creadas con `>>` no terminan—, el rango llega hasta el final del archivo y
> **se lleva puestas todas las entradas siguientes**.

Ese `.env` es el único archivo de `oracle/` con datos concretos; el resto es genérico y puede
quedarse en el repositorio para la próxima vez.

---

## Comprobar que quedó limpia

```bash
ssh $SSH 'echo "── contenedores ──"; docker ps -a 2>/dev/null || echo "docker no está instalado"; echo "── volúmenes ──"; docker volume ls 2>/dev/null; echo "── escuchando ──"; ss -tlnp | grep -v "127\."; echo "── disco ──"; df -h /'
```

Lo esperado: sin contenedores, sin volúmenes, sólo el puerto 22 escuchando, y el disco de
vuelta en unos pocos GB.

Y desde afuera, que no quede nada respondiendo:

```bash
for p in 80 443 9092; do timeout 3 bash -c "</dev/tcp/$IP/$p" 2>/dev/null && echo "$p TODAVÍA ABIERTO" || echo "$p cerrado"; done
```

---

## Terminar la instancia

La opción final: *Compute → Instances → tu instancia → Terminate*.

> **Pensalo dos veces.** En la capa gratuita las Ampere A1 se agotan seguido, y crear otra
> puede fallar con «Out of host capacity» durante días. **La capacidad asignada es mucho más
> fácil de conservar que de conseguir.**
>
> Si sólo querés dejar de gastar cupo, **detenerla** en vez de terminarla libera las OCPU-horas
> y conserva la instancia. Y si el problema es el cupo mensual, bajarla a 1 OCPU rinde más que
> apagarla a ratos.

Al terminarla, marcá también **«Permanently delete the attached boot volume»** si no vas a
reusarlo: un boot volume huérfano sigue ocupando tu cuota de almacenamiento gratuito y es un
motivo clásico de «no me deja crear la instancia nueva».
