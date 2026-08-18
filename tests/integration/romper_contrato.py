#!/usr/bin/env python3
"""
Rompe el contrato de un event type en bucle y devuelve `versiones codigo_final`.

Reproduce el ataque más barato contra el disco: cada PUT con un schema incompatible
estrena una versión mayor, y cada versión es un tópico con su propia retención. Sirve
para comprobar que el techo lo corta.

Uso:  romper_contrato.py <token> <fqn> <intentos>
"""

import json
import sys
import urllib.error
import urllib.request


def peticion(url, token, cuerpo=None, metodo='GET'):
    return urllib.request.Request(
        url, data=cuerpo, method=metodo,
        headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'},
    )


def main() -> None:
    token, fqn, intentos = sys.argv[1], sys.argv[2], int(sys.argv[3])
    nombre = fqn.rsplit('.', 1)[1]

    creacion = json.dumps({'name': nombre, 'fields': [{'name': 'x', 'type': 'string'}]}).encode()
    try:
        urllib.request.urlopen(peticion(
            'http://localhost:8080/api/v1/event-types', token, creacion, 'POST')).read()
    except urllib.error.HTTPError as error:
        print(f'0 {error.code}')
        return

    versiones = 1
    codigo = 0
    for i in range(intentos):
        # Cada cuerpo es incompatible con el anterior: cambia el nombre del campo, que es
        # lo que Avro no puede reconciliar.
        cuerpo = json.dumps({'fields': [{'name': f'campo{i}', 'type': 'int'}]}).encode()
        try:
            urllib.request.urlopen(peticion(
                f'http://localhost:8080/api/v1/event-types/{fqn}', token, cuerpo, 'PUT')).read()
            versiones += 1
        except urllib.error.HTTPError as error:
            codigo = error.code
            break
        except Exception:
            break

    print(f'{versiones} {codigo}')


if __name__ == '__main__':
    main()
