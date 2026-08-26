# El registro DNS en Cloudflare. Reemplaza al `curl` manual a DuckDNS del paso 5 de
# ORACLE.md: acá el registro se crea/actualiza en el mismo `apply` que crea la instancia,
# leyendo su IP real en vez de necesitar que alguien la copie a mano.
#
# MODO DNS-ONLY, SIEMPRE. `proxied` va hardcodeado en `false` y no es una variable — no es
# una preferencia, es un requisito: el proxy naranja de Cloudflare terminaría intermediando
# el HTTP y rompería la validación HTTP-01 de certbot (ORACLE.md, sección 8), y no puede
# proxiar Kafka en el 9092 porque es TCP crudo, no HTTP. Ver ADR-019, "Opción 4".

locals {
  # citypass.mrfranco.net.ar, no sólo "citypass" — la v5 del provider de Cloudflare
  # rompió contra la v4 justo acá: `name` ahora pide el hostname completo, no el
  # subdominio relativo a la zona.
  dns_fqdn = "${var.dns_subdomain}.${var.cloudflare_zone_name}"
}

data "cloudflare_zone" "this" {
  filter = {
    name = var.cloudflare_zone_name
  }
}

resource "cloudflare_dns_record" "vm" {
  zone_id = data.cloudflare_zone.this.id
  name    = local.dns_fqdn
  type    = "A"
  content = oci_core_instance.vm.public_ip
  ttl     = var.dns_ttl
  proxied = false
  comment = "Gestionado por Terraform — infrastructure/terraform/oracle-single (ADR-019)"
}
