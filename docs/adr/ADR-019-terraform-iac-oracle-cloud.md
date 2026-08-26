# ADR-019: Terraform como IaC para la VM de Oracle Cloud y el DNS

**Estado:** Aceptado
**Fecha:** 2026-08-26

---

## Contexto

[ADR-016](ADR-016-iaas-oracle-cloud.md) eligió IaaS en Oracle Cloud **justamente para tener
infraestructura que administrar y documentar**: red, VM, security lists, despliegue. Hoy esa
infraestructura existe, pero declarada en prosa —
[deployment/oracle-single/ORACLE.md](../../deployment/oracle-single/ORACLE.md), once pasos
manuales en la consola de Oracle— y no en código verificable. Eso deja tres problemas
concretos:

- **No hay forma de saber, sin entrar a la consola, qué infraestructura existe realmente.**
  La guía dice qué *deberías* haber hecho; no hay nada que compare eso contra lo que hay.
- **Recrear la VM es repetir once pasos a mano.** Pasa más seguido de lo que parece: la
  propia guía advierte que las A1 sufren *"Out of host capacity"* y que conviene no terminar
  nunca la instancia una vez conseguida — precisamente porque recrearla es costoso.
- **Un cambio de firewall o de shape no tiene *diff* ni revisión.** Hoy se aplica clickeando
  en la Security List; nadie ve qué cambió hasta que algo deja de andar.

Se suma un requisito nuevo: el dominio de producción, `citypass.mrfranco.net.ar`, vive en
**Cloudflare**, y hoy el equivalente en la guía (el `curl` a DuckDNS del paso 5 de
ORACLE.md) es manual. Cloudflare tiene un provider de Terraform oficial y confiable —a
diferencia de DuckDNS, que no lo tiene—, así que administrar ese registro también como
código es viable.

Importa una aclaración de arquitectura antes de decidir el alcance: el
[reverse-proxy](../../infrastructure/reverse-proxy/nginx.conf.template) sirve todos los
servicios bajo **un solo hostname**, separados por ruta (`/`, `/api/`, `/auth/`) y, en el
caso de Kafka, por **puerto** (9092) sobre ese mismo hostname — no hay subdominios por
servicio. El DNS que hay que administrar es, entonces, un único registro `A`.

## Opciones consideradas

### 1. Seguir enteramente manual

Descartada de entrada: es la razón de ser de este ADR. No dejaría nada nuevo para la
dimensión DevOps & Cloud más allá de lo que ya cubre el ADR-016, y arrastra los tres
problemas de arriba indefinidamente.

### 2. Alcance de Terraform: ¿hasta dónde llega?

Tres niveles posibles:

- **Sólo la nube** (VCN, subnet, security list, la instancia) — el SO y el despliegue de
  la app siguen con los scripts actuales (`preflight.sh`, `deploy.sh`, la emisión de
  certbot).
- **+ Bootstrap del SO vía `cloud-init`** — Terraform además instala Docker, expande el
  disco, abre `iptables`, en el primer arranque de la VM.
- **End-to-end**, hasta el `docker compose up` corriendo.

Se eligió el primero. Los otros dos duplicarían lógica que ya existe y está probada en
`preflight.sh`/`ORACLE.md`, y mezclarían dos responsabilidades distintas —aprovisionar
*qué existe* en la nube versus configurar *qué corre* dentro de la máquina— en la misma
herramienta. El caso end-to-end además choca con el orden estricto que exige la emisión
del certificado (nginx no arranca sin certificado, certbot necesita el puerto 80 libre) y
metería secretos del `.env` en un flujo pensado para infraestructura, no para
configuración de aplicación.

### 3. Dónde vive el estado (`tfstate`)

- **Local, gitignored** — un archivo en la máquina de quien aplica los cambios.
- **Remoto**, en un bucket de OCI Object Storage, con bloqueo para trabajo concurrente.

Se eligió local. El proyecto tiene un solo operador aplicando cambios de infraestructura;
un backend remoto resuelve un problema —dos personas aplicando a la vez— que acá no existe,
a cambio de un setup adicional (crear el bucket, credenciales aparte) que no compra nada
todavía. Documentado como mejora disponible si el proyecto pasara a tener varios operadores.

### 4. Gestión del DNS

- **Cloudflare vía Terraform**, usando el registro que ya tiene el usuario.
- **Seguir manual**, como con DuckDNS en `ORACLE.md`.

Se eligió Terraform, porque a diferencia de DuckDNS, Cloudflare **sí** tiene un provider
oficial. El registro se crea en modo **DNS-only (sin proxy)**, nunca con el proxy naranja
de Cloudflare: el proxy intermediaría el HTTP y rompería la validación HTTP-01 de certbot,
y no puede proxiar Kafka en 9092 porque es TCP crudo, no HTTP.

### 5. IP pública: reservada o efímera

`ORACLE.md` ya documenta que la IP efímera de Oracle sobrevive a reinicios y a
stop/start —no es el caso de AWS, que sí las cambia— y que reservarla es sólo una red de
seguridad opcional. Con Terraform gestionando también el DNS, esa red de seguridad importa
todavía menos: si la IP cambiara al recrear la instancia, el registro de Cloudflare se
reajusta solo en el mismo `apply`. Se mantiene efímera; reservarla queda como mejora de una
línea, no implementada.

## Decisión

Adoptar **Terraform** para provisionar la capa de nube de la VM de Oracle —red, firewall e
instancia— y el registro DNS en Cloudflare que apunta a ella:

- **Red:** VCN, internet gateway, route table, subnet pública.
- **Firewall:** una security list con ingreso sólo en 22, 80, 443 y 9092 —los mismos cuatro
  puertos que documenta `ORACLE.md`, ahora declarados en vez de clickeados—.
- **Cómputo:** una instancia `VM.Standard.A1.Flex`, con el shape que fija el
  [ADR-016](ADR-016-iaas-oracle-cloud.md) como valor por defecto —2 OCPU, 12 GB de
  memoria, imagen Ubuntu 24.04 `aarch64`, boot volume de 200 GB— parametrizado para poder
  ajustarse sin tocar código si el cupo gratuito de la cuenta cambiara.
- **DNS:** un registro `A` en Cloudflare para `citypass.mrfranco.net.ar`, en modo DNS-only,
  que Terraform mantiene apuntado a la IP de la instancia.

Todo lo que hoy empieza en el paso 4 de `ORACLE.md` —preparar el sistema operativo, abrir
puertos en `iptables` dentro de la VM, emitir el certificado, levantar el `docker
compose`— **sigue a cargo de los scripts existentes**. Terraform no los reemplaza ni los
orquesta; entrega una VM lista para que esos scripts corran, con la red y el firewall ya
declarados.

El estado se guarda **local, en `.gitignore`**. Sin backend remoto.

## Consecuencias

### Positivas

- La red, el firewall y la VM quedan declarados en código versionado y revisable, en vez de
  en una guía en prosa que sólo describe la intención. Cumple lo que el ADR-016 dejó
  pendiente: material concreto para la dimensión DevOps & Cloud.
- Recrear la instancia —si se pierde, o si conviene reintentar en otra época por el *"Out
  of host capacity"* que documenta `ORACLE.md`— es un `terraform apply`, no once pasos
  manuales.
- `terraform plan` muestra el cambio antes de aplicarlo: abrir un puerto de más, o cambiar
  el shape sin querer, se ve en un diff antes de tocar la nube real, no se descubre después
  con la comprobación manual del paso 10.4.
- El DNS deja de depender de acordarse de correr un comando a mano cada vez que la IP
  pudiera cambiar: se resuelve en el mismo `apply` que crea o actualiza la instancia.

### Negativas

- Se suma una herramienta nueva —Terraform, con dos providers— con su propia curva de
  aprendizaje para quien nunca la usó.
- El `tfstate` local es un punto de fragilidad: si se pierde el archivo, Terraform deja de
  saber qué administra. Mitigado por ser pocos recursos, fáciles de reimportar o recrear si
  hiciera falta.
- Queda una frontera explícita entre lo que administra Terraform (la nube) y lo que siguen
  administrando los scripts existentes (el sistema operativo y la aplicación). Alguien
  nuevo en el proyecto tiene que entender dónde termina una capa y empieza la otra;
  documentado en el README de
  `infrastructure/terraform/oracle-single/`.
- Se suman dos secretos nuevos para administrar —las credenciales de API de Oracle y el
  token de Cloudflare—, aparte de los que ya existen para SSH y GHCR.

## Referencias

- [ADR-016](ADR-016-iaas-oracle-cloud.md) — IaaS sobre PaaS, y Oracle Cloud como proveedor
- [deployment/oracle-single/ORACLE.md](../../deployment/oracle-single/ORACLE.md) — la guía
  manual que esta decisión reemplaza en su parte de red, firewall y VM
- [infrastructure/reverse-proxy/nginx.conf.template](../../infrastructure/reverse-proxy/nginx.conf.template) —
  por qué el DNS es un solo hostname y no un subdominio por servicio
- `infrastructure/terraform/oracle-single/README.md` — cómo se usa (una vez agregado)
