#!/usr/bin/env bash
# Comprueba que ningún contenedor corra como root.
#
# No impide un compromiso, pero encarece la escalada: una ejecución remota de código en un
# proceso root deja al atacante como root dentro del contenedor, que es el primer escalón
# de cualquier fuga hacia el host.
#
# Es fácil de perder sin darse cuenta: alcanza con que alguien agregue un RUN después del
# USER, o con reescribir un Dockerfile y olvidarse la línea. El contenedor arranca igual.
cd "$(dirname "$0")/../.." || exit 1
source tests/integration/comun.sh

echo "▶ usuarios de los contenedores"

activos=$(docker compose ps --format '{{.Name}}' 2>/dev/null)
[ -z "$activos" ] && { omitir "el stack no está levantado"; terminar; }

for contenedor in $activos; do
    uid=$(docker exec "$contenedor" id -u 2>/dev/null)
    usuario=$(docker exec "$contenedor" id -un 2>/dev/null || echo "$uid")

    if [ -z "$uid" ]; then
        # Algunas imágenes mínimas no traen `id`; se informa en vez de dar por buena.
        omitir "$contenedor no permite consultar el usuario"
        continue
    fi
    afirmar "$contenedor no corre como root (es '$usuario')" "no-root" \
        "$([ "$uid" = "0" ] && echo root || echo no-root)"
done

# Los volúmenes del gateway tienen que pertenecer a su usuario: si quedaran de root, el
# gateway arrancaría igual y recién fallaría al registrar el primer event type.
if contenedor_activo event-gateway; then
    for dir in /app/schemas /app/data; do
        propietario=$(docker exec event-gateway stat -c '%U' "$dir" 2>/dev/null)
        esperado=$(docker exec event-gateway id -un 2>/dev/null)
        afirmar "$dir pertenece al usuario del gateway" "$esperado" "$propietario"
    done
fi

terminar
