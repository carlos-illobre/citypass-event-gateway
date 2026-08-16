#!/usr/bin/env python3
"""
Imprime `salud|ultimo_error` de la primera regla de alerta de Grafana.

Vive en un archivo y no como `python3 -c` dentro del script de shell porque ahí las
comillas y las barras invertidas se escapan dos veces —una la shell y otra Python— y es
donde se cuelan los errores que hacen que el script mida algo distinto de lo que dice.

Lee el JSON de la API de reglas por la entrada estándar.
"""

import json
import sys


def main() -> None:
    try:
        datos = json.load(sys.stdin)
    except Exception:
        print('|sin respuesta')
        return

    for grupo in datos.get('data', {}).get('groups', []):
        for regla in grupo.get('rules', []):
            print(f"{regla.get('health', '')}|{regla.get('lastError', '')}")
            return

    print('|no hay reglas cargadas')


if __name__ == '__main__':
    main()
