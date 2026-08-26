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

## Pendiente — para poder escribir el código

1. **Subdominio/dominio exacto** a usar (ej. `citypass.tudominio.com`) — falta que el
   usuario lo confirme.
2. **Shape de la VM:** ¿el default en las variables Terraform queda fijo en 2 OCPU / 12 GB
   (lo que dice el ADR-016) o sin default, a elegir en cada `apply`? Recomendación dada:
   con default, sigue siendo override-able.

## Pendiente de proceso (no de contenido)

- **Pushear `feat/iac` a `origin`** (`github.com/carlos-illobre/citypass-event-gateway`).
  Sin esto, este archivo y cualquier commit posterior **no llegan al otro dispositivo** —
  la sincronización entre dispositivos depende 100% de git, no de la sesión de Claude ni
  de su memoria local. Se le preguntó al usuario si proceder y quedó sin confirmar.
  **Primer paso al retomar: preguntar si ya se pusheó, y si no, pushear antes de seguir.**

## Entregables planeados (una vez resueltos los pendientes de contenido)

En `infrastructure/terraform/oracle-single/`:
- `.tf`: `versions.tf`, `providers.tf` (oci + cloudflare), `network.tf`, `compute.tf`,
  `dns.tf`, `variables.tf`, `outputs.tf`
- `terraform.tfvars.example` (plantilla sin secretos) + entrada en `.gitignore` del repo
  para `terraform.tfvars`, `*.tfstate*`, `.terraform/`
- `README.md` en el estilo del resto del repo (ver `ORACLE.md` como referencia de tono),
  explicando init/plan/apply desde cero — el usuario nunca usó Terraform
- **ADR-019** en `docs/adr/`, documentando la decisión, en el tono de los ADRs existentes
  (ver `docs/adr/README.md` para el índice y el formato)

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
