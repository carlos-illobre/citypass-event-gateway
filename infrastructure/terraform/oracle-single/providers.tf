# Configuración de los providers. Ningún valor concreto acá — todo sale de variables, que
# a su vez salen de terraform.tfvars (local, en .gitignore) o de variables de entorno
# TF_VAR_*. Este archivo se versiona; los secretos, nunca.

provider "oci" {
  tenancy_ocid     = var.tenancy_ocid
  user_ocid        = var.user_ocid
  fingerprint      = var.fingerprint
  private_key_path = pathexpand(var.private_key_path)
  region           = var.region
}

provider "cloudflare" {
  api_token = var.cloudflare_api_token
}
