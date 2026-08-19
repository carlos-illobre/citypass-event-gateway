#!/usr/bin/env python3
"""
Publica eventos hasta superar cierta cantidad de bytes, y devuelve cuántos entraron.

Lo usa `retencion-kafka.sh` para llenar un tópico por encima de su retención. Vive en su
propio archivo y no dentro del script de shell porque ahí quedaría como un heredoc
anidado dentro de otro, que es difícil de leer y fácil de romper.

Un 429 no es un fallo: el rate limit es por namespace y por minuto, así que se espera a
la ventana siguiente y se sigue. Sin eso, el resultado dependería de si alguien usó el
mismo namespace en el último minuto.

Uso:  publicar_hasta.py <token> <bytes-objetivo> [fqn]
"""

import json
import sys
import time
import urllib.error
import urllib.request

TAMANO_EVENTO = 20000
FACTOR = 2.2          # se publica más que la retención para forzar varias rotaciones
LIMITE_SEGUNDOS = 300


def main() -> None:
    token = sys.argv[1]
    objetivo_bytes = int(sys.argv[2])
    fqn = sys.argv[3] if len(sys.argv) > 3 else 'com.citypass.movilidad.PruebaRetencionAuto'

    url = f'http://localhost:8080/api/v1/event-types/{fqn}/events'
    relleno = 'x' * TAMANO_EVENTO
    objetivo = int(objetivo_bytes * FACTOR / TAMANO_EVENTO) + 5

    publicados = 0
    corte = time.time() + LIMITE_SEGUNDOS

    while publicados < objetivo and time.time() < corte:
        cuerpo = json.dumps({'id': str(publicados), 'relleno': relleno}).encode()
        peticion = urllib.request.Request(
            url, data=cuerpo, method='POST',
            headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'},
        )
        try:
            urllib.request.urlopen(peticion).read()
            publicados += 1
        except urllib.error.HTTPError as error:
            if error.code == 429:
                time.sleep(20)
            else:
                break
        except Exception:
            break

    print(publicados)


if __name__ == '__main__':
    main()
