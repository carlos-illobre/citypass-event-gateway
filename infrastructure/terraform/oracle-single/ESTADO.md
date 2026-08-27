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
> Rama de trabajo: `feat/iac` (creada desde `main`), **ya pusheada a `origin`** y al día.

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
   usuario, no el `claude/...` autogenerado).
7. **Documentación viva del trabajo:** este archivo (`ESTADO.md`), no `CLAUDE.md` de la
   raíz (compartido con el equipo, el usuario prefirió no tocarlo) ni `CLAUDE.md` anidado
   (se explicó que hubiera dado auto-carga al entrar Claude a esta carpeta; el usuario
   prefirió igual `ESTADO.md`, con el costo de tener que pedirlo a mano en cada sesión
   nueva).
8. **Dinámica de trabajo desde esta sesión: aprendizaje práctico.** El usuario pidió
   explícitamente dejar de que Claude ejecute los comandos de Terraform/infra por él —
   quiere correrlos él mismo y que Claude guíe/explique. Guardado también en la memoria
   de Claude (`hands-on-learning-iac`, local a esta máquina); este punto queda acá además
   porque memoria no viaja entre dispositivos. Coherente con "git hands-off" (el usuario
   hace sus propios commits, salvo que pida explícitamente lo contrario como pasó en esta
   sesión al pedir commitear y pushear el lock file + este archivo).

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
4. **Módulo Terraform commiteado y pusheado.** Los 2 commits con todo el módulo
   (`versions.tf`, `providers.tf`, `variables.tf`, `network.tf`, `compute.tf`, `dns.tf`,
   `outputs.tf`, `terraform.tfvars.example`, `README.md`) y el ADR-019 ya están en
   `origin/feat/iac`. El usuario los revisó y commiteó él mismo.
5. **Terraform CLI instalado** (v1.15.9 — hay v1.16.0 disponible, no bloqueante).
   `terraform init -backend=false` y `terraform validate` corridos por el usuario:
   **ambos en verde.** Generó `.terraform.lock.hcl`, que **sí se versiona** (a diferencia
   de `.terraform/`) — quedó pendiente de commitear, ver "Qué falta".
6. **OCI CLI resultó innecesario.** El usuario no pudo instalarlo siguiendo la doc de
   HashiCorp; no hace falta para nada de este módulo. La API key de OCI se generó
   enteramente desde la consola web: *My profile → API keys → Add API key → Generate API
   Key Pair*, que da los 4 datos (`tenancy_ocid`, `user_ocid`, `fingerprint`, y la private
   key para descargar) sin CLI.
7. **`terraform.tfvars` creado** (local, gitignoreado, verificado con `git status` que no
   quedó trackeado). Tiene cargados: los 5 valores de OCI, `cloudflare_api_token`, y
   presumiblemente el resto (zona, subdominio, etc. — no confirmado explícitamente pero
   `terraform plan` no se quejó de ninguno de esos).
8. **Gotcha de Windows ya resuelto:** backslashes en rutas de Windows dentro de
   `terraform.tfvars` rompen el parser de HCL (`\U`, `\f`, etc. son secuencias de escape).
   Hubo que cambiar `private_key_path` de `C:\Users\...` a `C:/Users/...` (forward
   slashes). Si aparece un `Invalid escape sequence` en cualquier otra variable de ruta
   (ej. si el usuario toca `ssh_public_key_path` a mano con backslashes), es lo mismo.

## Estado actual de `terraform plan` — EN CURSO, 2 errores pendientes

Corrido por el usuario, sin `-backend=false` (plan real, con auth contra OCI y
Cloudflare). La parte de OCI **no tiró error** (auth con la API key funcionando). Quedan
dos errores sueltos, ninguno de red/compute:

1. **SSH key faltante.** `compute.tf` línea 55 (`file(pathexpand(var.ssh_public_key_path))`)
   no encontró archivo en `~/.ssh/id_rsa.pub`. Se le explicó al usuario que esto es
   independiente de las credenciales de OCI/Cloudflare — es el par de claves para que
   *él* se loguee por SSH a la VM una vez creada; Terraform sólo lee el contenido de la
   pública para inyectarla como `authorized_keys`. Se le indicó generar uno con
   `ssh-keygen -t ed25519` si no tenía, y ajustar `ssh_public_key_path` en el `.tfvars`
   si el nombre de archivo no es `id_rsa.pub`. **No confirmado si ya lo resolvió.**
2. **Cloudflare `403 Invalid access token`** (`data.cloudflare_zone.this` en `dns.tf`).
   El token en sí es rechazado por Cloudflare, antes de evaluar permisos. Causas más
   probables, a chequear en la próxima sesión:
   - Que haya creado una **Global API Key** (dashboard → *API Keys*) en vez de un
     **API Token** (dashboard → *API Tokens → Create Token*) — son mecanismos distintos,
     el provider de Terraform espera un Token tipo Bearer.
   - Espacio/salto de línea de más al pegar el token en `terraform.tfvars`.
   - Permisos del token no incluyen *Zone → DNS → Edit* sobre la zona correcta, o expiró.
   Se le pasó al usuario un `curl` contra
   `https://api.cloudflare.com/client/v4/user/tokens/verify` con el token en el header
   `Authorization: Bearer` para aislar el problema sin pasar por Terraform. **Sin
   respuesta todavía — retomar por acá.**

## Qué falta

1. **Resolver el error de Cloudflare** (ver arriba) — primer paso al retomar.
2. Confirmar que el SSH key también quedó resuelto (o resolverlo si no).
3. Volver a correr `terraform plan` hasta que dé limpio (0 errores, review del `Plan: N to
   add, 0 to change, 0 to destroy.`).
4. `terraform apply`.
5. Seguir con `ORACLE.md` desde la sección 4 (el README de esta carpeta lo explica).
6. **Commitear `.terraform.lock.hcl`** (nuevo, generado por `terraform init`, sí se
   versiona) y este `ESTADO.md` actualizado — pedido explícito del usuario en esta
   sesión, pusheando a `origin/feat/iac`.

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
- **Cómo Terraform resuelve variables (`variables.tf` → `var.X` en cualquier `.tf` →
  auto-carga de `terraform.tfvars` por convención de nombre, no por ninguna referencia
  explícita en el código).** Orden de precedencia explicado: env vars `TF_VAR_*` <
  `terraform.tfvars` < `terraform.tfvars.json` < `*.auto.tfvars` < `-var`/`-var-file` en
  CLI. Por eso `terraform.tfvars.example` es seguro de commitear (el `.example` rompe el
  nombre mágico) y `terraform.tfvars` no.
- Que la API key de OCI, el token de Cloudflare y el par SSH son **tres autenticaciones
  independientes** para tres cosas distintas (Terraform↔OCI, Terraform↔Cloudflare,
  usuario↔VM por SSH) — confusión que surgió naturalmente al ver el error de SSH y
  pensar que era parte de las credenciales de nube.
