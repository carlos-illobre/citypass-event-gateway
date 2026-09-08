# Versiones de Terraform y de los providers.
#
# Un solo provider: `oracle/oci`, el oficial de Oracle — el viejo `hashicorp/oci` quedó
# discontinuado. El DNS NO se administra desde acá; es un paso manual, ver ADR-020
# ("Opción 4") y el README de esta carpeta.
#
# Va fijado en la MAYOR (`>= 5.0.0`, sin techo) y no en un patch exacto: alcanza para
# protegerse de un cambio incompatible, sin quedar clavado a una versión puntual.

terraform {
  required_version = ">= 1.8.0"

  required_providers {
    oci = {
      source  = "oracle/oci"
      version = ">= 5.0.0"
    }
  }
}
