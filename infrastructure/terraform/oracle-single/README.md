# Terraform — la VM de Oracle y el DNS, como código

Qué son estos archivos, qué hacen exactamente y qué dejan afuera a propósito. Pensado para
alguien que nunca usó Terraform.

> **Esto reemplaza sólo una parte de [ORACLE.md](../../../deployment/oracle-single/ORACLE.md):**
> la creación de la VM, la red y el firewall (secciones 2 y 7 de esa guía). Todo lo demás
> —instalar Docker, abrir puertos dentro de la VM, emitir el certificado, levantar el
> compose— sigue siendo esa misma guía, corrida a mano después de que la VM exista. El
> porqué de esta frontera está en [ADR-019](../../../docs/adr/ADR-019-terraform-iac-oracle-cloud.md).

---

## Contenido

1. [Qué hace falta antes de empezar](#1-qué-hace-falta-antes-de-empezar)
2. [Conseguir las credenciales](#2-conseguir-las-credenciales)
3. [Configurar](#3-configurar)
4. [Los tres comandos](#4-los-tres-comandos)
5. [Qué queda creado](#5-qué-queda-creado)
6. [Seguir con ORACLE.md](#6-seguir-con-oraclemd)
7. [Cambiar algo después](#7-cambiar-algo-después)
8. [Destruir todo](#8-destruir-todo)
9. [Problemas frecuentes](#9-problemas-frecuentes)

---

## 1. Qué hace falta antes de empezar

- **Terraform instalado.** `terraform version` tiene que andar. Si no está,
  [instrucciones oficiales](https://developer.hashicorp.com/terraform/install) — es un
  solo binario, sin dependencias.
- **Una cuenta de Oracle Cloud**, con su *home region* ya elegida (esa decisión es
  irreversible — si todavía no la creaste, ver ADR-019 para las candidatas).
- **El dominio en Cloudflare**, con la zona (`mrfranco.net.ar`) ya dada de alta ahí.
- **Un par de claves SSH.** Si no tenés una: `ssh-keygen -t ed25519`.

## 2. Conseguir las credenciales

### De Oracle

Consola de OCI → ícono de perfil (arriba a la derecha) → **My profile**.

| Variable | Dónde está |
|---|---|
| `tenancy_ocid` | En la misma página, o en *Tenancy information* del menú del perfil |
| `user_ocid` | En **My profile**, arriba de todo |
| `region` | La *home region* de tu cuenta — no se puede cambiar |
| `compartment_ocid` | Igual a `tenancy_ocid` si no creaste compartments propios |

Para `fingerprint` y `private_key_path` hace falta generar una API key:

1. En **My profile**, pestaña **API keys** → **Add API key**.
2. Elegí **Generate API key pair** y descargá la clave privada — Oracle no la muestra dos
   veces.
3. Guardala en un lugar estable, ej. `~/.oci/oci_api_key.pem`, y dale permisos
   restrictivos: `chmod 600 ~/.oci/oci_api_key.pem`.
4. Al confirmar, la consola te muestra el `fingerprint`. Copialo.

### De Cloudflare

Dashboard de Cloudflare → ícono de perfil → **My Profile** → **API Tokens** → **Create
Token**.

Usá una plantilla personalizada, no la de "Edit zone DNS" genérica sin acotar: restringila
a **Zone → DNS → Edit**, con el **Zone Resources** apuntando sólo a tu zona
(`mrfranco.net.ar`). Un token de alcance más amplio que el necesario es una llave de más.

## 3. Configurar

```bash
cd infrastructure/terraform/oracle-single
cp terraform.tfvars.example terraform.tfvars
```

Editá `terraform.tfvars` con los valores de arriba. **Ese archivo no se versiona** —está
en `.gitignore`— así que tus credenciales no van a parar al repositorio.

## 4. Los tres comandos

Siempre en este orden, siempre desde esta carpeta:

```bash
terraform init
```

Descarga los providers (`oracle/oci` y `cloudflare/cloudflare`) y prepara el directorio de
trabajo. Se corre una vez, y de nuevo cada vez que cambien las versiones en `versions.tf`.

```bash
terraform plan
```

**No cambia nada.** Es la vista previa: compara tu código contra lo que ya existe y te
dice qué va a crear, modificar o destruir. Es el paso que conviene leer con atención antes
de seguir — si algo se ve raro (por ejemplo, que quiera *recrear* la instancia en vez de
sólo crearla), es el momento de parar y entender por qué, no después.

```bash
terraform apply
```

Te vuelve a mostrar el mismo plan y pide confirmación (escribís `yes`). Recién ahí llama a
las APIs de Oracle y Cloudflare y crea todo. Tarda unos minutos — la instancia tarda en
provisionar.

Si en algún momento te da **"Out of host capacity"** al crear la instancia: no es un error
de este código, es Oracle sin capacidad A1 disponible en tu región en ese momento —
`ORACLE.md` ya lo advertía para el flujo manual. Se soluciona reintentando
(`terraform apply` de nuevo) más tarde.

## 5. Qué queda creado

| Recurso | Qué es |
|---|---|
| Una VCN, un internet gateway, una route table, una subnet | La red de la instancia |
| Una security list | Ingreso sólo en 22, 80, 443 y 9092 — igual que ORACLE.md, sección 7 |
| Una instancia `VM.Standard.A1.Flex` | Ubuntu 24.04 arm64, pelada — sin Docker, sin nada del stack |
| Un registro `A` en Cloudflare | `citypass.mrfranco.net.ar` → la IP de la instancia, modo DNS-only |

Al final del `apply`, Terraform imprime los outputs — entre ellos, el comando de SSH ya
armado y el recordatorio de los próximos pasos. Para volver a verlos sin aplicar nada:

```bash
terraform output
```

## 6. Seguir con ORACLE.md

La VM existe pero está pelada. De acá en adelante es
[ORACLE.md](../../../deployment/oracle-single/ORACLE.md), **arrancando en la sección 4**
("Verificar que la máquina esté lista") — las secciones 2 y 7 de esa guía ya las hizo
Terraform, saltealas.

Un par de cosas cambian respecto a la guía original, porque ahí asumía DuckDNS:

- El dominio ya está resuelto por Cloudflare — no hace falta el paso 5 completo, sólo
  usar `citypass.mrfranco.net.ar` (o el valor de `dns_subdomain`/`cloudflare_zone_name`
  que hayas puesto) donde la guía dice `TU_DOMINIO`.
- La IP para el SSH es el output `instance_public_ip` de acá, no algo que copies de la
  consola de Oracle a mano.

## 7. Cambiar algo después

Por ejemplo, bajar el shape si el cupo gratuito de la cuenta cambiara: editás
`terraform.tfvars` (o pasás `-var`), corrés `terraform plan` para ver el impacto, y
`terraform apply` si el diff es el esperado. Terraform actualiza sólo lo que cambió — no
recrea la VM entera por cambiar, por ejemplo, el TTL del DNS.

## 8. Destruir todo

```bash
terraform destroy
```

Borra la instancia, la red y el registro DNS. **Es irreversible** y no toca nada de lo que
vive dentro de la VM en volúmenes de Docker —de hecho, los borra junto con la instancia—,
así que si hay algo que conservar, hacé el backup que describe
[DEPLOYMENT.md](../../../docs/DEPLOYMENT.md#backup) antes.

## 9. Problemas frecuentes

| Síntoma | Causa probable |
|---|---|
| `Error: 401-NotAuthenticated` | `fingerprint` o `private_key_path` no coinciden con la API key generada en el paso 2 |
| `Error: 404-NotAuthorizedOrNotFound` en `compartment_ocid` | El compartment no existe o el usuario no tiene permisos ahí — probá con `tenancy_ocid` |
| `Out of host capacity` al crear la instancia | Oracle sin capacidad A1 en tu región en ese momento. Reintentar `apply` más tarde |
| `Error 400` al buscar la zona de Cloudflare | `cloudflare_zone_name` tiene que ser el dominio raíz tal como está en el dashboard (ej. `mrfranco.net.ar`), no el subdominio |
| El `plan` quiere recrear la instancia sin que hayas tocado nada | Salió una imagen de Ubuntu 24.04 más nueva — no debería pasar, `compute.tf` ignora ese cambio a propósito; si ocurre, revisar el bloque `lifecycle` |

---

## Referencias

- [ADR-019](../../../docs/adr/ADR-019-terraform-iac-oracle-cloud.md) — por qué Terraform, y por qué este alcance
- [ADR-016](../../../docs/adr/ADR-016-iaas-oracle-cloud.md) — por qué Oracle, y los valores del shape
- [ORACLE.md](../../../deployment/oracle-single/ORACLE.md) — todo lo que sigue después de la VM
