# Versiones de Terraform y de los providers.
#
# Dos providers porque son dos nubes distintas: la VM vive en Oracle (`oracle/oci`, el
# oficial de Oracle — el viejo `hashicorp/oci` quedó discontinuado) y el DNS vive en
# Cloudflare (`cloudflare/cloudflare`). Ver ADR-019 para el porqué de cada uno.
#
# Los dos van fijados en la MAYOR (`>= 5.0.0`, sin techo) y no en un patch exacto: la v5 de
# Cloudflare trajo cambios que rompen contra la v4 (el recurso pasó de `cloudflare_record` a
# `cloudflare_dns_record`, y `name` pasó a pedir el dominio completo en vez del subdominio
# relativo a la zona) — este módulo ya está escrito para v5, así que exige esa mayor.

terraform {
  required_version = ">= 1.8.0"

  required_providers {
    oci = {
      source  = "oracle/oci"
      version = ">= 5.0.0"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = ">= 5.0.0"
    }
  }
}
