#!/usr/bin/env python3
"""Imprime el estado del primer webhook de un tópico. Lee el JSON por stdin."""
import json
import sys

fqn = sys.argv[1] if len(sys.argv) > 1 else None
try:
    subs = [s for s in json.load(sys.stdin) if fqn is None or s.get('topic') == fqn]
    print(subs[0].get('status', '?') if subs else 'sin-suscripciones')
except Exception:
    print('error')
