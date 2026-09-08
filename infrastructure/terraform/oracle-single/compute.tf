# La instancia. Es el equivalente en código de ORACLE.md, sección "2. Crear la instancia".
#
# Lo que NO hace este archivo, a propósito (ver ADR-020): no instala Docker, no expande el
# disco, no abre iptables adentro de la VM, no clona el repo ni levanta el compose. Todo
# eso sigue siendo preflight.sh / ORACLE.md / deploy.sh, corridos a mano después del
# `terraform apply`. Esta VM sale "pelada" —sólo el sistema operativo de la imagen—, lista
# para que esos scripts hagan su parte.

# Las availability domains son a nivel de tenancy, no de compartment — por eso
# compartment_id acá es tenancy_ocid y no compartment_ocid, siguiendo el patrón habitual
# del provider.
data "oci_identity_availability_domains" "ads" {
  compartment_id = var.tenancy_ocid
}

# Busca la imagen Ubuntu 24.04 más reciente que sea compatible con el shape elegido —el
# filtro por shape es lo que garantiza que sea la variante aarch64 y no amd64, sin tener
# que hardcodear un OCID de imagen que además es distinto por región.
#
# ORACLE.md ya lo explica: las A1 son ARM, así que TODAS las imágenes tienen que publicar
# arm64. Ubuntu lo hace, por eso esta búsqueda no necesita filtrar arquitectura a mano.
data "oci_core_images" "ubuntu_arm" {
  compartment_id           = var.compartment_ocid
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "24.04"
  shape                    = var.instance_shape
  sort_by                  = "TIMECREATED"
  sort_order                = "DESC"
}

resource "oci_core_instance" "vm" {
  availability_domain = data.oci_identity_availability_domains.ads.availability_domains[0].name
  compartment_id       = var.compartment_ocid
  display_name         = "${var.project_name}-vm"
  shape                 = var.instance_shape

  shape_config {
    ocpus         = var.instance_ocpus
    memory_in_gbs = var.instance_memory_in_gbs
  }

  source_details {
    source_type             = "image"
    source_id                = data.oci_core_images.ubuntu_arm.images[0].id
    boot_volume_size_in_gbs = var.boot_volume_size_in_gbs
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.this.id
    assign_public_ip = true
    hostname_label    = var.project_name
  }

  metadata = {
    ssh_authorized_keys = file(pathexpand(var.ssh_public_key_path))
  }

  # Sin esto, `terraform destroy` deja el boot volume huérfano en la cuenta — ocupando
  # cupo gratuito de almacenamiento sin que ninguna VM lo use ni Terraform lo administre.
  preserve_boot_volume = false

  # Qué imagen de Ubuntu 24.04 está publicada el día del apply puede variar; no tiene
  # sentido que un redespliegue recree la instancia entera sólo porque Oracle publicó una
  # imagen más nueva desde la última vez. Se ignora el bloque completo porque
  # `ignore_changes` no admite apuntar a un atributo suelto dentro de un bloque anidado.
  lifecycle {
    ignore_changes = [source_details]
  }
}
