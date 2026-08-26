# Estado del trabajo — Terraform IaC (Oracle Cloud)

> **Archivo personal de trabajo en curso.** No es documentación del proyecto ni se referencia
> desde ningún otro lado — se puede borrar sin dejar nada roto una vez que este trabajo esté
> terminado y mergeado. Vive acá (y no en la memoria de Claude) a propósito: así viaja entre
> dispositivos vía git, sin depender de en qué máquina se abra la sesión.
>
> **Para retomar en una sesión nueva (cualquier dispositivo):** pedile a Claude que lea este
> archivo. Por ejemplo: *"seguimos con el IaC de Oracle, mirá
> infrastructure/terraform/oracle-single/ESTADO.md"*.
>
> Rama de trabajo: `feat/iac` (creada desde `main`, todavía no pusheada a `origin` —
> ver "Pendientes de proceso" abajo).

## Objetivo

Incorporar Terraform como IaC para el despliegue en Oracle Cloud, para la dimensión
DevOps & Cloud del TP "TPO DAP2". El repo ya trae todo el contexto de referencia:

- [ADR-016](../../../docs/adr/ADR-016-iaas-oracle-cloud.md) — por qué IaaS y por qué Oracle
- [deployment/oracle-single/ORACLE.md](../../../deployment/oracle-single/ORACLE.md) — la guía manual de 11 pasos que hoy se sigue a mano
- [deployment/oracle-single/.env.oracle](../../../deployment/oracle-single/.env.oracle) — config de recursos de la VM
- [deployment/oracle-single/deploy.sh](../../../deployment/oracle-single/deploy.sh) — despliegue de la app (no lo toca Terraform)
- [docs/DEPLOYMENT.md](../../../docs/DEPLOYMENT.md) — despliegue en producción, genérico

**No se pudo leer la consigna/rúbrica exacta del TP** (el proyecto "TPO DAP2" vive en otra
app, sin conector autorizado en esta sesión). Si el usuario trae la consigna, usarla; si no,
el criterio es el del ADR-016: declarar y documentar la nube de punta a punta alcanza.

## Decisiones ya tomadas (confirmadas por el usuario)

1. **Alcance de Terraform:** sólo red + VM + firewall — VCN, internet gateway, route table,
   subnet, security list (puertos 22/80/443/9092), la instancia `VM.Standard.A1.Flex`.
   El bootstrap del SO (Docker, growfs, iptables) y el despliegue de la app **siguen
   manuales**, con los scripts existentes. Nada de cloud-init ni end-to-end.
2. **State de Terraform:** local, en `.gitignore`. Sin backend remoto.
3. **DNS:** el dominio del usuario está en **Cloudflare**. Terraform usa también el
   provider `cloudflare` para el registro `A`, leyendo la IP pública de la instancia OCI
   como output. **Siempre en modo DNS-only (nube gris), nunca proxy naranja** — el proxy
   de Cloudflare rompería la validación HTTP-01 de certbot y no puede proxiar Kafka (TCP
   crudo) en el puerto 9092.
4. **IP pública:** efímera, no reservada. Si cambiara al recrear la VM, Terraform reajusta
   el registro de Cloudflare solo. La IP reservada queda documentada como mejora opcional,
   no implementada.
5. **El reverse-proxy (nginx) dentro de la VM no cambia.** Sigue siendo el único punto de
   entrada real: TLS con Let's Encrypt, ruteo HTTP, stream de Kafka en 9092. Cloudflare
   sólo resuelve DNS, no toca el tráfico — esto se lo confirmé explícitamente al usuario
   porque preguntó si el proxy "se movía" a Cloudflare (no, sigue igual).
6. **Rama de trabajo:** `feat/iac`, creada desde `main` (siguiendo el nombre que pidió el
   usuario, no el `claude/...` autogenerado). Sin conflictos al crearla en este worktree —
   si en otro dispositivo falló, no se identificó la causa (probablemente otro estado local
   en ese dispositivo/clon).
7. **Documentación viva del trabajo:** este archivo (`ESTADO.md`), no `CLAUDE.md` de la
   raíz (compartido con el equipo, el usuario prefirió no tocarlo) ni `CLAUDE.md` anidado
   (se explicó que hubiera dado auto-carga al entrar Claude a esta carpeta; el usuario
   prefirió igual `ESTADO.md`, con el costo de tener que pedirlo a mano en cada sesión
   nueva).

## Ya no pendiente — resuelto

1. **Dominio confirmado:** `citypass.mrfranco.net.ar`. Es **un solo hostname**, no
   subdominios por servicio — confirmado leyendo
   [nginx.conf.template](../../reverse-proxy/nginx.conf.template): el proxy separa por
   ruta (`/`, `/api/`, `/auth/`) y Kafka reusa el mismo hostname por el puerto 9092
   (bloque `stream`). Un solo registro `A` en Cloudflare alcanza.
2. **Shape de la VM:** default fijo según ADR-016 (2 OCPU / 12 GB, `VM.Standard.A1.Flex`,
   Ubuntu 24.04 arm64, boot volume 200 GB), parametrizado para poder override-earse.
3. **[ADR-019](../../../docs/adr/ADR-019-terraform-iac-oracle-cloud.md) ya escrita**,
   documentando todo lo de arriba, y el índice de `docs/adr/README.md` actualizado.

## Pendiente de proceso (no de contenido)

- **Pushear `feat/iac` a `origin`** (`github.com/carlos-illobre/citypass-event-gateway`).
  Sin esto, este archivo y cualquier commit posterior **no llegan al otro dispositivo** —
  la sincronización entre dispositivos depende 100% de git, no de la sesión de Claude ni
  de su memoria local. Se le preguntó al usuario si proceder y quedó sin confirmar.
  **Primer paso al retomar: preguntar si ya se pusheó, y si no, pushear antes de seguir.**

## Entregables — YA ESCRITOS, pendientes de que el usuario los revise y commitee

Todo en `infrastructure/terraform/oracle-single/`, sin commitear todavía (el usuario hace
sus propios commits — ver feedback guardada en memoria, "git hands-off"):

- `versions.tf`, `providers.tf`, `variables.tf`, `network.tf`, `compute.tf`, `dns.tf`,
  `outputs.tf` — módulo completo: VCN + subnet + IGW + route table + security list
  (22/80/443/9092) + instancia `VM.Standard.A1.Flex` (defaults del ADR-016) + registro
  `cloudflare_dns_record` en modo DNS-only.
- `terraform.tfvars.example` — plantilla con marcadores, mismo estilo que `.env.oracle`.
- `.gitignore` de la raíz del repo actualizado (sección "Terraform").
- `README.md` de esta carpeta — guía completa desde cero (conseguir credenciales, los
  tres comandos, qué sigue con ORACLE.md, troubleshooting).
- **ADR-019** ya escrita y en el índice de `docs/adr/README.md`.

**Detalles técnicos verificados con búsquedas web antes de escribir** (importante si se
retoma esto y algo no coincide con lo esperado):
- Provider correcto: `oracle/oci` (NO `hashicorp/oci`, discontinuado).
- Recurso Cloudflare v5: `cloudflare_dns_record` (no `cloudflare_record`, es de v4).
- En v5, el atributo `name` del registro DNS pide el **hostname completo**
  (`citypass.mrfranco.net.ar`), no el subdominio relativo a la zona — cambió entre v4 y
  v5, es un error fácil de cometer si se copia un ejemplo viejo.
- Sin Terraform CLI instalado en el entorno de escritura: el HCL se revisó a mano
  (llaves, heredocs) pero **nunca se corrió `terraform validate` de verdad**. Primer paso
  recomendado al usuario: `terraform init && terraform validate` antes de `plan`.

## Qué falta (lo único que queda)

1. El usuario revisa el código y lo commitea/pushea a su criterio.
2. Terminar de crear la cuenta de Oracle (home region ya decidida: São Paulo,
   `sa-saopaulo-1`, o Santiago como alternativa) y generar la API key.
3. Crear el token de Cloudflare (Zone → DNS → Edit, acotado a la zona).
4. `cp terraform.tfvars.example terraform.tfvars`, completar, y correr
   `init` → `validate` → `plan` → `apply`.
5. Seguir con `ORACLE.md` desde la sección 4 (el README de esta carpeta lo explica).

## Requisitos que el usuario va a necesitar para correr `apply` (no para escribir el código)

No deben pegarse en el chat ni en archivos versionados — van a `terraform.tfvars` local,
gitignoreado:

- **Oracle** (consola → *My profile → API keys*): tenancy OCID, user OCID, fingerprint de
  la API key, la private key, región, compartment OCID.
- **Cloudflare:** API token con permiso *Zone → DNS → Edit* sobre la zona del dominio.

## Conceptos ya explicados al usuario (no volver a explicar desde cero, sólo repasar si pregunta)

- Qué es IaC y por qué (declarativo, idempotente) — comparado paso a paso contra ORACLE.md.
- Qué es Terraform: HCL, `plan`/`apply`, provider, y **qué es el `tfstate`** (por qué existe,
  por qué siempre va gitignoreado, local vs. remoto).
- La frontera entre "aprovisionar la nube" (Terraform) y "configurar la máquina" (scripts
  actuales) — el usuario la entendió y la eligió explícitamente en la opción 1.
- Por qué el registro de Cloudflare va en modo DNS-only y no proxy.
- La diferencia entre memoria de Claude (local a esta máquina), un archivo cualquiera en
  git (sincroniza pero no se auto-carga) y `CLAUDE.md` (sincroniza y se auto-carga) — este
  archivo es la consecuencia práctica de esa charla.
