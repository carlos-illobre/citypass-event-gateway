# ─────────────────────────────────────────────────────────────────────────────
# Autenticación con Oracle Cloud (OCI)
# ─────────────────────────────────────────────────────────────────────────────
#
# Los cinco salen de Cuenta → My profile → API keys, al generar una API key nueva.
# Ninguno tiene default: son de tu cuenta, no algo que el proyecto pueda asumir.

variable "tenancy_ocid" {
  description = "OCID de la tenancy (Cuenta → Tenancy information)."
  type        = string
}

variable "user_ocid" {
  description = "OCID del usuario (Cuenta → My profile)."
  type        = string
}

variable "fingerprint" {
  description = "Fingerprint de la API key, tal como lo muestra la consola al generarla."
  type        = string
}

variable "private_key_path" {
  description = "Ruta al archivo .pem de la clave privada de la API key. Admite ~."
  type        = string
}

variable "region" {
  description = <<-EOT
    Región de OCI, con el formato de la consola (ej. "sa-saopaulo-1"). Tiene que ser tu
    *home region* — Always Free sólo funciona ahí, y esa elección no se puede cambiar
    después de crear la cuenta. Ver ADR-019 para las candidatas evaluadas.
  EOT
  type        = string
}

variable "compartment_ocid" {
  description = <<-EOT
    OCID del compartment donde crear los recursos. Para una cuenta nueva sin compartments
    propios, es el mismo que tenancy_ocid — la raíz también es un compartment válido.
  EOT
  type        = string
}


# ─────────────────────────────────────────────────────────────────────────────
# Acceso a la instancia
# ─────────────────────────────────────────────────────────────────────────────

variable "ssh_public_key_path" {
  description = "Ruta a tu clave pública SSH (la .pub, nunca la privada). Admite ~."
  type        = string
  default     = "~/.ssh/id_rsa.pub"
}


# ─────────────────────────────────────────────────────────────────────────────
# Cómputo — shape de la instancia
# ─────────────────────────────────────────────────────────────────────────────
#
# Los defaults son los que fija el ADR-016 para el Always Free de Oracle: shape ARM
# Ampere A1, 2 OCPU, 12 GB, Ubuntu 24.04, 200 GB de boot volume. Quedan como variables
# —no hardcodeados— por si el cupo gratuito de tu cuenta cambiara (ORACLE.md ya advierte
# que Oracle lo viene ajustando) y hicicera falta bajarlos sin tocar código.

variable "instance_shape" {
  description = "Shape de la instancia. Tiene que ser un Flex para que OCPU/memoria se puedan ajustar."
  type        = string
  default     = "VM.Standard.A1.Flex"
}

variable "instance_ocpus" {
  description = "OCPU asignadas. 2 es lo que documenta el ADR-016 para el Always Free."
  type        = number
  default     = 2
}

variable "instance_memory_in_gbs" {
  description = "Memoria en GB. 12 es lo que documenta el ADR-016 para el Always Free."
  type        = number
  default     = 12
}

variable "boot_volume_size_in_gbs" {
  description = <<-EOT
    Tamaño del boot volume en GB. 200 coincide con el cupo gratuito total de
    almacenamiento de Oracle (ver ORACLE.md, sección 2): no hay a dónde crecer sin pagar.
  EOT
  type        = number
  default     = 200
}


# ─────────────────────────────────────────────────────────────────────────────
# Red
# ─────────────────────────────────────────────────────────────────────────────

variable "vcn_cidr" {
  description = "Bloque CIDR de la VCN."
  type        = string
  default     = "10.0.0.0/16"
}

variable "subnet_cidr" {
  description = "Bloque CIDR de la subnet pública, dentro del rango de la VCN."
  type        = string
  default     = "10.0.1.0/24"
}


# ─────────────────────────────────────────────────────────────────────────────
# Nombrado
# ─────────────────────────────────────────────────────────────────────────────

variable "project_name" {
  description = "Prefijo para el nombre de los recursos en la consola de Oracle."
  type        = string
  default     = "citypass"
}


# ─────────────────────────────────────────────────────────────────────────────
# Dominio público
# ─────────────────────────────────────────────────────────────────────────────
#
# Terraform NO administra el DNS (ver ADR-019, "Opción 4"): el registro A se crea a mano
# en Cloudflare, una vez por entorno. Esta variable existe igual porque el hostname sí es
# dato de infraestructura — es lo que hay que apuntar a la IP de la instancia, y lo que
# después va en el .env de la VM. El output `next_steps` lo usa para recordar el paso.

variable "public_domain" {
  description = <<-EOT
    Hostname público completo de este entorno (ej. "citypass.tudominio.com").
    Es el registro A que hay que crear a mano en Cloudflare apuntando a la IP de la
    instancia, en modo DNS-only (nube gris). Es también el valor que después va en
    PUBLIC_DOMAIN y KAFKA_ADVERTISED_HOST del .env de la instancia
    (ver deployment/oracle-single/.env.oracle).

    Sin default a propósito, igual que las variables de OCI: el dominio es tuyo, no algo
    que el proyecto deba asumir.
  EOT
  type        = string
}
