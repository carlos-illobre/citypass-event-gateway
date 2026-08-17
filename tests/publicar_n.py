#!/usr/bin/env python3
"""
Publica N eventos en un event type y devuelve cuántos entraron.

Un 429 no es fallo: el rate limit es por namespace y por minuto, así que se espera a la
ventana siguiente. Sin eso el resultado dependería de si alguien usó el namespace recién.

Uso:  publicar_n.py <token> <fqn> <cantidad>
"""
import json
import sys
import time
import urllib.error
import urllib.request

token, fqn, cantidad = sys.argv[1], sys.argv[2], int(sys.argv[3])
url = f'http://localhost:8080/api/v1/event-types/{fqn}/events'
publicados = 0
corte = time.time() + 240

while publicados < cantidad and time.time() < corte:
    peticion = urllib.request.Request(
        url, data=json.dumps({'id': str(publicados)}).encode(), method='POST',
        headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'})
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
