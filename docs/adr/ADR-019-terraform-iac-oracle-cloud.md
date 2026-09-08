# ADR-019: Terraform como IaC para la VM de Oracle Cloud

**Estado:** Aceptado
**Fecha:** 2026-08-26 (revisado 2026-09-07: el DNS queda fuera del alcance, ver "Opción 4")

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

Se suma un requisito nuevo: el dominio de producción vive en **Cloudflare**, y hoy el
equivalente en la guía (el `curl` a DuckDNS del paso 5 de ORACLE.md) es manual. Cloudflare
tiene un provider de Terraform oficial y confiable —a diferencia de DuckDNS, que no lo
tiene—, así que administrar ese registro también como código sería técnicamente viable.
Si conviene hacerlo es otra pregunta, y se trata en la "Opción 4".

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

- **Cloudflare vía Terraform**, con el provider oficial.
- **Seguir manual**, cargando el registro `A` en el dashboard.

Se eligió **seguir manual**. La primera opción llegó a implementarse —`dns.tf`, con el
`data.cloudflare_zone` y el `cloudflare_dns_record`— y se descartó después de probarla, por
tres razones que sólo se hicieron visibles al usarla:

- **La frecuencia no lo justifica.** Es *un* registro `A` por entorno, y habrá dos
  entornos (testing y producción) sobre VMs pensadas para no apagarse ni recrearse. La
  automatización se paga con la repetición; acá no hay repetición que amortice el costo.
- **Acopla dos cosas que no tienen por qué estarlo.** El `data.cloudflare_zone` se
  resuelve durante el `plan`, así que un problema con Cloudflare —un token con permisos
  de menos, en el caso concreto que se dio— aborta el plan **entero** y deja sin
  aprovisionar la red y la VM, que no dependen del DNS en absoluto. Un registro DNS que se
  toca dos veces en la vida no debería poder bloquear el aprovisionamiento de la
  infraestructura.
- **Cuesta un secreto más.** Un API token de Cloudflare, con su ciclo de vida
  (creación, permisos, expiración, rotación), para ahorrar una carga manual por entorno.

El registro se crea, a mano, en modo **DNS-only (sin proxy)**, nunca con el proxy naranja
de Cloudflare: el proxy intermediaría el HTTP y rompería la validación HTTP-01 de certbot,
y no puede proxiar Kafka en 9092 porque es TCP crudo, no HTTP. Esto vale igual que antes —
lo que cambia es quién lo hace, no cómo queda configurado.

### 5. IP pública: reservada o efímera

`ORACLE.md` ya documenta que la IP efímera de Oracle sobrevive a reinicios y a
stop/start —no es el caso de AWS, que sí las cambia— y que reservarla es sólo una red de
seguridad opcional. Se mantiene efímera; reservarla queda como mejora de una línea, no
implementada.

Con el DNS manual (opción 4), el único escenario en que la IP importa es **destruir y
recrear** la instancia: ahí hay que editar el registro `A` con la IP nueva, que el `apply`
imprime como output. Es el mismo escenario poco frecuente que hace que el DNS como código
no se pague.

## Decisión

Adoptar **Terraform** para provisionar la capa de nube de la VM de Oracle —red, firewall e
instancia—, y nada más:

- **Red:** VCN, internet gateway, route table, subnet pública.
- **Firewall:** una security list con ingreso sólo en 22, 80, 443 y 9092 —los mismos cuatro
  puertos que documenta `ORACLE.md`, ahora declarados en vez de clickeados—.
- **Cómputo:** una instancia `VM.Standard.A1.Flex`, con el shape que fija el
  [ADR-016](ADR-016-iaas-oracle-cloud.md) como valor por defecto —2 OCPU, 12 GB de
  memoria, imagen Ubuntu 24.04 `aarch64`, boot volume de 200 GB— parametrizado para poder
  ajustarse sin tocar código si el cupo gratuito de la cuenta cambiara.
**El DNS queda explícitamente afuera.** El registro `A` del entorno, en modo DNS-only, se
carga a mano en el dashboard de Cloudflare apuntando a la IP que Terraform imprime como
output. El módulo no declara el provider de Cloudflare ni necesita credenciales suyas.

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
- El aprovisionamiento no depende de ningún servicio de terceros más allá de Oracle: un
  problema de DNS no puede bloquear la creación de la red ni de la VM.
- Un solo secreto nuevo (las credenciales de API de Oracle) en vez de dos.

### Negativas

- Se suma una herramienta nueva —Terraform— con su propia curva de aprendizaje para quien
  nunca la usó.
- **El registro DNS es un paso manual y queda fuera del código.** Hay que acordarse de
  crearlo antes de emitir el certificado (certbot valida por HTTP-01 y necesita que el
  dominio resuelva), y de actualizarlo si alguna vez se recrea la instancia. Está
  documentado como paso explícito en el README del módulo y en el output `next_steps` del
  `apply`, que imprime el hostname y la IP a cargar. `terraform destroy` tampoco lo borra:
  queda apuntando a una IP inexistente hasta que alguien lo limpie.
- El `tfstate` local es un punto de fragilidad: si se pierde el archivo, Terraform deja de
  saber qué administra. Mitigado por ser pocos recursos, fáciles de reimportar o recrear si
  hiciera falta.
- Queda una frontera explícita entre lo que administra Terraform (la nube) y lo que siguen
  administrando los scripts existentes (el sistema operativo y la aplicación). Alguien
  nuevo en el proyecto tiene que entender dónde termina una capa y empieza la otra;
  documentado en el README de
  `infrastructure/terraform/oracle-single/`.
- Se suma un secreto nuevo para administrar —las credenciales de API de Oracle—, aparte de
  los que ya existen para SSH y GHCR.

## Referencias

- [ADR-016](ADR-016-iaas-oracle-cloud.md) — IaaS sobre PaaS, y Oracle Cloud como proveedor
- [deployment/oracle-single/ORACLE.md](../../deployment/oracle-single/ORACLE.md) — la guía
  manual que esta decisión reemplaza en su parte de red, firewall y VM
- [infrastructure/reverse-proxy/nginx.conf.template](../../infrastructure/reverse-proxy/nginx.conf.template) —
  por qué el DNS es un solo hostname y no un subdominio por servicio
- `infrastructure/terraform/oracle-single/README.md` — cómo se usa (una vez agregado)
