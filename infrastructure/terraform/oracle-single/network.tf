# Red: VCN, salida a internet, y el firewall.
#
# Es el equivalente en código de lo que ORACLE.md hace a mano en la consola (sección "2.
# Crear la instancia" da la VM por sentada dentro de una VCN default; acá se declara esa
# VCN explícitamente) y sobre todo del paso 7, "Abrir los puertos": la security list de
# abajo es EXACTAMENTE esas cuatro reglas, ahora declaradas en vez de clickeadas.

resource "oci_core_vcn" "this" {
  compartment_id = var.compartment_ocid
  cidr_blocks    = [var.vcn_cidr]
  display_name   = "${var.project_name}-vcn"

  # dns_label: sólo alfanumérico, máx. 15 caracteres, sin guiones — por eso no usa
  # project_name tal cual si tuviera un guión medio.
  dns_label = "citypassvcn"
}

resource "oci_core_internet_gateway" "this" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.project_name}-igw"
  enabled        = true
}

resource "oci_core_route_table" "this" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.project_name}-rt"

  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.this.id
  }
}

# La security list: sólo 22 (SSH), 80, 443 y 9092 de entrada — los mismos cuatro puertos
# que documentan ORACLE.md (sección 7) y docs/DEPLOYMENT.md (sección 4), y ningún otro.
# 8080, 8081, 8083, 8084, 8090, 9090 y 9091 (Schema Registry, kafka-ui, Prometheus,
# Grafana, etc.) NO se abren acá: quedan alcanzables sólo desde dentro de la VM, tal como
# exige SECURITY.md. Esto resuelve la mitad "consola de Oracle" del paso 7 — la otra
# mitad, las reglas de iptables DENTRO de la instancia, sigue a cargo de preflight.sh /
# ORACLE.md, porque vive en el sistema operativo, no en la nube.
resource "oci_core_security_list" "this" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.project_name}-security-list"

  # Salida sin restricción: es lo mismo que "Stateless desmarcado" de la guía manual —
  # con reglas stateful el tráfico de vuelta se permite solo, no hace falta declararlo.
  egress_security_rules {
    protocol    = "all"
    destination = "0.0.0.0/0"
  }

  ingress_security_rules {
    protocol = "6" # TCP
    source   = "0.0.0.0/0"
    tcp_options {
      min = 22
      max = 22
    }
  }

  ingress_security_rules {
    protocol = "6"
    source   = "0.0.0.0/0"
    tcp_options {
      min = 80
      max = 80
    }
  }

  ingress_security_rules {
    protocol = "6"
    source   = "0.0.0.0/0"
    tcp_options {
      min = 443
      max = 443
    }
  }

  ingress_security_rules {
    protocol = "6"
    source   = "0.0.0.0/0"
    tcp_options {
      min = 9092
      max = 9092
    }
  }
}

resource "oci_core_subnet" "this" {
  compartment_id             = var.compartment_ocid
  vcn_id                     = oci_core_vcn.this.id
  cidr_block                 = var.subnet_cidr
  display_name               = "${var.project_name}-subnet"
  dns_label                  = "citypasssub"
  route_table_id             = oci_core_route_table.this.id
  security_list_ids          = [oci_core_security_list.this.id]
  prohibit_public_ip_on_vnic = false
}
