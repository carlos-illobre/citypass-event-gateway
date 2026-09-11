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
3. **DNS: FUERA del alcance de Terraform (revisado el 2026-09-07).** Originalmente se
   implementó con el provider `cloudflare` (`dns.tf`), y se dio marcha atrás después de
   probarlo — ver "Por qué se sacó el DNS" más abajo. El registro `A` se carga a mano en
   el dashboard, **siempre en modo DNS-only (nube gris), nunca proxy naranja** (el proxy
   rompería la validación HTTP-01 de certbot y no puede proxiar Kafka, que es TCP crudo,
   en el 9092).
4. **IP pública:** efímera, no reservada. Sobrevive a reinicios y stop/start; sólo cambia
   si se destruye y recrea la instancia, y en ese caso hay que editar el registro `A` a
   mano con la IP que imprime el output. La IP reservada queda documentada como mejora
   opcional, no implementada.
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
9. **Dos ambientes, decidido con el equipo (2026-09-11): primero test, prod después.**
   El ambiente que se está por levantar (el bloqueado por capacidad A1, arriba) es
   **testing**; producción se levanta más adelante, en otra VM aparte.
   - **Dominio:** `test.citypass.mrfranco.net.ar` para test. El dominio "pelado"
     (`citypass.mrfranco.net.ar`) queda reservado para prod — no se le agrega prefijo,
     así no se toca dos veces el dominio final.
   - **Ya aplicado:** `DOMINIO` en `deployment/oracle-single/.env` (local, gitignoreado)
     actualizado a `test.citypass.mrfranco.net.ar`. Falta actualizar `public_domain` en
     `terraform.tfvars` cuando exista (hoy no existe el archivo, sólo el `.example` — se
     crea recién al retomar el `apply`).
   - **Mapa de impacto revisado con Claude** (sesión 2026-09-11): el dominio real sólo
     vive en archivos locales/gitignoreados (`deployment/oracle-single/.env`,
     `terraform.tfvars`) y en el `.env` que termina en la VM (generado desde
     `.env.oracle` vía el `sed` del paso 6 de ORACLE.md — variables `PUBLIC_DOMAIN`,
     `KAFKA_ADVERTISED_HOST`, `TOKEN_ISSUER`, `AUTH_CORS_ORIGIN`, `GATEWAY_CORS_ORIGIN`,
     `LOGIN_API_URL`, `GATEWAY_API_URL`, `DISPATCHER_API_URL`). Nada del código
     (`application.yml`, `docker-compose.yml`, `nginx.conf.template`) ni de la
     documentación versionada tiene el dominio hardcodeado — todo sale de variables de
     entorno o usa placeholders (`TU_DOMINIO`). El certificado usa `--cert-name citypass`
     fijo, así que nginx no depende del dominio real en ningún lado.
   - **Como el ambiente de test es una VM nueva** (todavía no provisionada), alcanza con
     correr el paso 6 de ORACLE.md una sola vez con el `DOMINIO` nuevo — no hace falta
     editar variables sueltas a mano. Conviene hacerlo recién ahora que `.env.oracle` ya
     trae los cambios de `main` (PR #26, variables de webhooks reagrupadas) para no
     regenerar el `.env` de la instancia dos veces.
   - **Pendiente, pospuesto a propósito:** `deploy.sh` sólo lee un
     `deployment/oracle-single/.env` fijo, sin noción de "a qué ambiente" apunta. Con dos
     VMs reales existe riesgo de desplegar al servidor equivocado si se usa el mismo
     checkout para los dos. El usuario decidió **no resolverlo todavía** — se retoma
     cuando prod exista de verdad (opciones ya evaluadas: extender `deploy.sh` para
     elegir entre `.env.test`/`.env.prod`, o mantener un checkout separado por ambiente).
   - **Terraform para dos ambientes:** con state local (ya decidido, sin backend remoto),
     falta elegir entre `tfvars` separados por ambiente (`-var-file`) o *workspaces* antes
     de aplicar el segundo. No decidido todavía — no urge mientras el bloqueante de
     capacidad A1 siga sin resolverse.

## Ya no pendiente — resuelto

1. **Dominio confirmado.** Es **un solo hostname** por entorno, no subdominios por
   servicio — confirmado leyendo
   [nginx.conf.template](../../reverse-proxy/nginx.conf.template): el proxy separa por
   ruta (`/`, `/api/`, `/auth/`) y Kafka reusa el mismo hostname por el puerto 9092
   (bloque `stream`). Un solo registro `A` en Cloudflare alcanza. El valor concreto va en
   `terraform.tfvars` (local, gitignoreado): en la documentación se usan placeholders
   tipo `tudominio.com`, no el dominio real.
2. **Shape de la VM:** default fijo según ADR-016 (2 OCPU / 12 GB, `VM.Standard.A1.Flex`,
   Ubuntu 24.04 arm64, boot volume 200 GB), parametrizado para poder override-earse.
3. **[ADR-021](../../../docs/adr/ADR-021-terraform-iac-oracle-cloud.md) ya escrita**,
   documentando todo lo de arriba, y el índice de `docs/adr/README.md` actualizado.
4. **Módulo Terraform commiteado y pusheado.** Los 2 commits con todo el módulo
   (`versions.tf`, `providers.tf`, `variables.tf`, `network.tf`, `compute.tf`, `dns.tf`,
   `outputs.tf`, `terraform.tfvars.example`, `README.md`) y el ADR-021 ya están en
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

## Por qué se sacó el DNS de Terraform (2026-09-07)

Se llegó a implementar y probar `dns.tf`. El `terraform plan` real contra OCI **pasó sin
problemas** (la auth con la API key de Oracle funciona), pero Cloudflare falló dos veces
seguidas:

1. Primero un `401 Invalid API Token` — token inválido, se regeneró.
2. Con el token nuevo (verificado como válido y activo contra
   `/user/tokens/verify`), el `plan` volvió a fallar: `403 Forbidden`, code 9109, en
   `GET /zones?name=...`. Causa: al token le faltaba **Zone → Zone → Read**. Tenía sólo
   *DNS → Edit*, que alcanza para escribir el registro pero no para *buscar la zona por
   nombre*, que es lo que hace `data.cloudflare_zone`.

En ese punto el usuario decidió cambiar de estrategia y dejar el DNS manual. El
razonamiento (ahora documentado en el ADR-021, "Opción 4"): un registro `A` por entorno,
dos entornos, sobre VMs que no se apagan ni se recrean — la automatización no se amortiza,
cuesta un secreto más, y sobre todo **acopla el DNS al aprovisionamiento**: el
`data.cloudflare_zone` se evalúa en el `plan`, así que un problema de Cloudflare abortaba
el plan entero y dejaba sin crear la red y la VM, que no dependen del DNS.

## BLOQUEANTE: no hay capacidad A1 en Oracle (2026-09-08)

**El módulo funciona.** El `apply` crea la red entera sin problemas (VCN, internet gateway,
route table, subnet, security list — todo en el state) y falla **sólo** en
`oci_core_instance`:

```
Error: 500-InternalError, Out of host capacity.
POST https://iaas.sa-saopaulo-1.oraclecloud.com/20160918/instances
```

Lo verificado hasta ahora:

- **No es el provider.** La línea "This provider is 2 Update(s) behind" que agrega el
  mensaje es ruido: el provider de OCI la pone en todos sus errores. La respuesta viene
  del servidor de Oracle (trae `OPC request ID`), o sea que el request llegó, autenticó y
  se rechazó del lado de ellos. Un request mal armado daría 400, no este 500.
- **No es el availability domain.** `sa-saopaulo-1` tiene **un solo AD**, confirmado con
  `terraform console` sobre `data.oci_identity_availability_domains.ads`. No hay a dónde
  moverse; `compute.tf` toma `[0]` y es el único.
- **No es cuota en cero.** En *Limits, Quotas and Usage* → Compute →
  `standard-a1-core-count` figura **Usage 0, Service limit "Dynamic"**. "Dynamic" =
  Oracle no asigna un cupo fijo, lo decide por request según el tipo de cuenta. Un límite
  duro en 0 habría dado `LimitExceeded`, no `Out of host capacity`.
- **No es transitorio.** Se dejó un loop de `terraform apply` corriendo toda una noche.
  Cero éxitos.

**Diagnóstico:** la cuenta es **Free Trial**, y las cuentas de trial/Always Free se sirven
sólo de la capacidad que sobra después de las cuentas de pago. Con un único AD en la
región, no hay reintento que lo resuelva. (La priorización por tipo de cuenta es
comportamiento observado y muy reportado, no política documentada por Oracle — a
diferencia de los cupos del Always Free, que sí están documentados.)

**Esto golpea una premisa del ADR-016**, que eligió Oracle justamente por las A1
gratuitas: el cupo existe en el papel pero no se puede materializar con una cuenta de
trial. Queda como riesgo abierto.

Opciones sobre la mesa, sin decidir todavía:

1. **Upgrade a Pay As You Go.** Los límites "dynamic" aflojan con medio de pago activo, y
   las A1 dentro de 4 OCPU / 24 GB siguen sin facturar. Ojo: el boot volume de 200 GB es
   **todo** el cupo gratuito de block storage — un volumen de más ya cobra. Configurar
   budget alert ANTES de crear nada.
2. **Pedir aumento de límite** (Consola → Limits → *Request a service limit increase*).
   Gratis, tarda días, baja probabilidad en cuentas gratuitas. Se puede disparar en
   paralelo.
3. **Replantear el proveedor** para la entrega del TP, si la fecha aprieta.

Lo que ya se descartó por inviable: bajar a 1 OCPU (en A1 la memoria va atada a los cores,
6 GB por OCPU, y los techos de `.env.oracle` suman 6,5 GiB — el stack no entra),
`VM.Standard.E2.1.Micro` (1 GB de RAM), y cambiar de región (los recursos Always Free
viven sólo en la home region, que es irreversible).

## Estado actual del código

- **`dns.tf` eliminado**; el provider `cloudflare` sacado de `versions.tf` y
  `providers.tf`; las variables `cloudflare_*`, `dns_subdomain` y `dns_ttl` reemplazadas
  por una sola `public_domain` (sin default). `outputs.tf` ahora imprime el paso manual de
  DNS como paso 0 de `next_steps`, con hostname e IP ya resueltos.
- **ADR actualizado en el lugar** (título, contexto, opción 4 dada vuelta, decisión y
  consecuencias) + fila del índice `docs/adr/README.md`. Ojo: el README de ADRs dice que
  un ADR no se edita sino que se supersede — se editó igual porque **todavía no está
  mergeado a `main`**, vive sólo en `feat/iac`. **Pendiente de confirmar con el equipo**
  si prefieren un ADR nuevo que lo supersede.
- **Renumerado dos veces: 019 → 020 → 021.** Colisionó con dos ADR que el equipo mergeó a
  `main` mientras esta rama estaba abierta:
  - 2026-09-08: `ADR-019-firewall-en-la-vcn.md` (commit `5e547a2`) → el de Terraform pasó
    a 020.
  - 2026-09-10: `ADR-020-webhooks-en-su-propio-servicio.md` → pasó a **021**.

  Criterio, en los dos casos: **el número se lo queda el ADR que ya está en `main`**, que
  es el que tiene referencias vivas desde otros documentos. El conflicto de git fue las
  dos veces sólo la fila del índice `docs/adr/README.md`; se resuelve dejando todas las
  filas.

  **Causa de fondo:** esta rama lleva semanas abierta y `main` se mueve. El propio
  [ADR-018](../../../docs/adr/ADR-018-ramificacion-y-versionado.md) pide ramas de "vida de
  días y no de semanas" — mergear antes es lo que evita la tercera colisión. El número del
  ADR conviene fijarlo recién al abrir el PR, mirando qué hay en `main` en ese momento.
- **Relación con el ADR-019 (firewall), documentada.** No hay conflicto entre los dos: el
  019 decide *dónde* vive el control de acceso (la security list de la VCN, porque el DNAT
  de Docker saca los paquetes de `INPUT`) y el 020 decide *cómo se declara* esa security
  list (código en vez de consola). Además el módulo **deja sin efecto una negativa del
  019** —"la configuración vive fuera del repositorio, sin historial ni revisión por
  PR"—, cosa que quedó anotada en el 020 sin editar el 019.
- **Textos de `iptables` alineados** en `network.tf`, `outputs.tf` y el README del módulo:
  ya no dicen que iptables sea "la otra mitad" del firewall. Para 80/443/9092 no filtra
  nada; cubre `sshd` en el 22 y la última línea de los servicios internos.
- **README del módulo reescrito** en la parte de Cloudflare: sin credenciales, con una
  sección 6 nueva ("El DNS, a mano") y renumeración de las que seguían.
- **Documentación sin datos concretos:** pedido explícito del usuario — en la doc va
  `tudominio.com` / `citypass.tudominio.com`, no el dominio real. El valor real vive sólo
  en `terraform.tfvars`, que es local y gitignoreado.
- **SSH key: resuelta.** El usuario corre todo desde **WSL (distro `Ubuntu`, usuario
  `franco`)**, no desde Windows. Las claves están en `/home/franco/.ssh/id_ed25519(.pub)`,
  con permisos correctos. El `.tfvars` usa `~/...` en las dos rutas y resuelve contra
  `/home/franco`, así que la nota del "gotcha de Windows" de más arriba ya no aplica
  mientras se trabaje desde WSL. Desde Windows ese filesystem se ve en
  `\\wsl.localhost\Ubuntu\home\franco\`.

## Qué falta

**Todo esto está detrás del bloqueante de capacidad de arriba** — el `apply` no puede
completarse hasta resolverlo. El resto del flujo ya está listo y probado:

1. **Decidir cómo se destraba la capacidad A1** (PAYG / pedido de límite / otro
   proveedor). Es lo único que importa ahora.
2. `terraform apply` — la red ya está creada, sólo falta la instancia.
3. **Crear el registro `A` a mano** en Cloudflare (DNS-only) con la IP del output.
4. Seguir con `ORACLE.md` desde la sección 4 (el README de esta carpeta lo explica).
5. Commitear todo esto y pushear a `origin/feat/iac`.

Ya hechos (no repetir): limpieza de las líneas `cloudflare_*` del `terraform.tfvars`
local, `terraform init` con el lock file podado, y `terraform plan` limpio.

## Backup: qué guardar para poder administrar el server

Se repasó con el usuario (no tiene bucket, la idea es un Drive personal **cifrado** —
`.7z` con AES-256, `age` o `gpg`; las contraseñas chicas mejor en un gestor):

- **Tier 1, sin copia no hay vuelta:** la clave privada SSH (`/home/franco/.ssh/id_ed25519`
  — perderla deja la VM viva pero inaccesible salvo por consola serie o desprendiendo el
  boot volume); el `terraform.tfstate` (+ `.backup`) —que **es un secreto**, guarda OCIDs
  y la clave pública en claro, y sin él el próximo `plan` propone recrear todo—; el `.env`
  de la VM (las passwords de kafka-ui y Grafana se generan al azar en el envío y no quedan
  en ningún otro lado); y los tres volúmenes de datos (`docs/DEPLOYMENT.md#backup`).
- **Tier 2, regenerable pero cómodo:** `terraform.tfvars` y la clave de API de OCI
  (`/home/franco/.oci/oci_api_key.pem`).
- **No guardar:** certificados de Let's Encrypt (se re-emiten solos), `.terraform/`,
  imágenes Docker (están en GHCR).
- La copia del `tfstate` sólo sirve si se re-sube **después de cada `apply`**.
- Pendiente evaluado y postergado: la cuenta Always Free incluye Object Storage, así que
  un backend remoto es posible sin costo. No se hizo para no meter un cambio más en el
  aire; el ADR-021 justifica el state local por concurrencia, que es un problema distinto
  del de la copia de resguardo.

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
