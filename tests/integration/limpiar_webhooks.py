#!/usr/bin/env python3
"""
Da de baja las suscripciones propias a un tópico.

Con `--dejar-una` conserva la primera: sirve para probar el cortacircuitos, que es por
suscripción y necesita una sola activa para que "no abre ninguna conexión" signifique
algo.

Uso:  limpiar_webhooks.py <token> <fqn> [--dejar-una]
"""
import json
import sys
import urllib.request

token, fqn = sys.argv[1], sys.argv[2]
peticion = urllib.request.Request('http://localhost:8080/api/v1/subscriptions',
                                  headers={'Authorization': f'Bearer {token}'})
dejar_una = '--dejar-una' in sys.argv
propias = [s for s in json.load(urllib.request.urlopen(peticion)) if s.get('topic') == fqn]

for sub in (propias[1:] if dejar_una else propias):
    baja = urllib.request.Request(f"http://localhost:8080/api/v1/subscriptions/{sub['id']}",
                                  method='DELETE', headers={'Authorization': f'Bearer {token}'})
    try:
        urllib.request.urlopen(baja).read()
    except Exception:
        pass
