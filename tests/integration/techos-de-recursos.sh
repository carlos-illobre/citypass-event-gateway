#!/usr/bin/env bash
# Comprueba que los techos declarados en el .env estén realmente aplicados.
#
# Docker acepta un compose con mem_limit o logging mal escritos sin quejarse: los ignora
# y el contenedor arranca sin techo. Lo único que lo demuestra es preguntarle al
# contenedor en marcha.
cd "$(dirname "$0")/../.." || exit 1
source tests/integration/comun.sh

echo "▶ techos de recursos"

if ! contenedor_activo event-gateway; then
    omitir "el stack no está levantado (docker compose up -d)"; terminar
fi

leer_env() { grep -E "^$1=" .env 2>/dev/null | cut -d= -f2- | tr -d '"'; }

a_bytes() {
    local v="${1,,}"
    case "$v" in
        *k) echo $(( ${v%k} * 1024 )) ;;
        *m) echo $(( ${v%m} * 1024 * 1024 )) ;;
        *g) echo $(( ${v%g} * 1024 * 1024 * 1024 )) ;;
        *)  echo "$v" ;;
    esac
}

# ── memoria ──
declare -A esperados=(
    [kafka-authorizer]=MEM_LIMIT_KAFKA
    [schema-registry]=MEM_LIMIT_SCHEMA_REGISTRY
    [event-gateway]=MEM_LIMIT_EVENT_GATEWAY
    [kafka-ui]=MEM_LIMIT_KAFKA_UI
    [prometheus]=MEM_LIMIT_PROMETHEUS
    [grafana]=MEM_LIMIT_GRAFANA
    [anomaly-detector]=MEM_LIMIT_ANOMALY_DETECTOR
    [auth-simulator]=MEM_LIMIT_AUTH_SIMULATOR
    [event-gateway-ui]=MEM_LIMIT_EVENT_GATEWAY_UI
)
for contenedor in "${!esperados[@]}"; do
    contenedor_activo "$contenedor" || { omitir "$contenedor no está activo"; continue; }
    esperado=$(a_bytes "$(leer_env "${esperados[$contenedor]}")")
    real=$(docker inspect -f '{{.HostConfig.Memory}}' "$contenedor" 2>/dev/null)
    afirmar "$contenedor tiene el techo de memoria del .env" "$esperado" "$real"
done

# ── rotación de logs ──
#
# Sin esto el driver json-file no tiene límite y un servicio que loguee mucho llena el
# disco sin pasar por ningún otro control.
tam=$(leer_env LOG_MAX_SIZE)
arch=$(leer_env LOG_MAX_FILES)
for contenedor in event-gateway kafka-authorizer grafana; do
    contenedor_activo "$contenedor" || continue
    real_tam=$(docker inspect -f '{{index .HostConfig.LogConfig.Config "max-size"}}' "$contenedor" 2>/dev/null)
    real_arch=$(docker inspect -f '{{index .HostConfig.LogConfig.Config "max-file"}}' "$contenedor" 2>/dev/null)
    afirmar "$contenedor rota el log al tamaño del .env" "$tam" "$real_tam"
    afirmar "$contenedor guarda la cantidad de archivos del .env" "$arch" "$real_arch"
done

# ── superficie de escape ──
#
# Ningún contenedor debe ver el socket de Docker ni tener privilegios: es la vía por la
# que un proceso comprometido saldría del contenedor al host.
for contenedor in $(docker compose ps --format '{{.Name}}' 2>/dev/null); do
    monta_socket=$(docker inspect -f '{{range .Mounts}}{{.Source}} {{end}}' "$contenedor" 2>/dev/null | grep -c "docker.sock")
    privilegiado=$(docker inspect -f '{{.HostConfig.Privileged}}' "$contenedor" 2>/dev/null)
    afirmar "$contenedor no monta el socket de Docker" "0" "$monta_socket"
    afirmar "$contenedor no es privilegiado" "false" "$privilegiado"
done

terminar
