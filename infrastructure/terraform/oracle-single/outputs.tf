output "instance_public_ip" {
  description = "IP pública de la instancia. Es la que va en el registro DNS (ya gestionado acá) y la que usás para SSH."
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
    El hostname completo (citypass.mrfranco.net.ar por defecto). Es el mismo valor que
    después va en PUBLIC_DOMAIN y KAFKA_ADVERTISED_HOST del .env de la instancia —
    ver deployment/oracle-single/.env.oracle y ORACLE.md, paso 6.
  EOT
  value       = local.dns_fqdn
}

output "next_steps" {
  description = "Qué sigue después del apply — Terraform no hace nada de esto."
  value       = <<-EOT
    La VM y el DNS ya existen. Lo que sigue es manual, con ORACLE.md desde la sección 4:
      1. Verificar la máquina (preflight.sh) e instalar Docker.
      2. Clonar el repo y mandar el .env (sección 6).
      3. Abrir los puertos EN IPTABLES dentro de la VM (sección 7 — la Security List de
         Oracle ya la abrió Terraform, pero iptables es una capa aparte).
      4. Emitir el certificado (sección 8) y levantar el stack (sección 9).
  EOT
}
