output "instance_public_ip" {
  description = <<-EOT
    IP pública de la instancia. Es la que hay que cargar A MANO en el registro A de
    Cloudflare (Terraform no administra el DNS, ver ADR-020) y la que usás para SSH.
  EOT
  value       = oci_core_instance.vm.public_ip
}

output "instance_id" {
  description = "OCID de la instancia, por si hace falta buscarla en la consola de Oracle."
  value       = oci_core_instance.vm.id
}

output "ssh_command" {
  description = "Comando listo para conectarse. El usuario 'ubuntu' lo trae la imagen de Canonical."
  value       = "ssh -i ${var.private_key_path} ubuntu@${oci_core_instance.vm.public_ip}"
}

output "public_domain" {
  description = <<-EOT
    El hostname completo de este entorno, tal como lo pusiste en terraform.tfvars. Es el
    mismo valor que después va en PUBLIC_DOMAIN y KAFKA_ADVERTISED_HOST del .env de la
    instancia — ver deployment/oracle-single/.env.oracle y ORACLE.md, paso 6.
  EOT
  value       = var.public_domain
}

output "next_steps" {
  description = "Qué sigue después del apply — Terraform no hace nada de esto."
  value       = <<-EOT
    La VM ya existe. El DNS NO: Terraform no lo administra (ADR-020). Lo que sigue es
    manual, empezando por el registro DNS y siguiendo con ORACLE.md desde la sección 4:
      0. Crear/actualizar en Cloudflare el registro A de ${var.public_domain}
         apuntando a ${oci_core_instance.vm.public_ip}, en modo DNS-ONLY (nube gris,
         nunca proxy naranja). Va primero: certbot valida por HTTP-01 y necesita que el
         dominio ya resuelva a esta IP.
      1. Verificar la máquina (preflight.sh) e instalar Docker.
      2. Clonar el repo y mandar el .env (sección 6).
      3. Aplicar las reglas de iptables de la sección 7. Ojo: el acceso desde internet ya
         lo decide la security list, que Terraform declaró (ADR-019). Esas reglas cubren
         otra cosa —sshd y los servicios internos—, no los puertos de los contenedores.
      4. Emitir el certificado (sección 8) y levantar el stack (sección 9).
  EOT
}
