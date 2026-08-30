#!/usr/bin/env python3
"""
Resume el mutation testing de un proyecto Gradle, en Markdown.

Lo usa el pipeline para escribir en `$GITHUB_STEP_SUMMARY`, igual que `coverage.py` con
la cobertura, de modo que cada ejecución muestre el número sin que nadie lo mantenga a
mano.

**Lo que informa no es una compuerta.** El mutation score no tiene umbral en este
repositorio, y es deliberado: existen los mutantes *equivalentes*, que producen código con
el mismo comportamiento y que ningún test puede matar —los tres de `kafka-authorizer` son
métodos del SPI de Kafka que ya devuelven lista vacía y PIT «muta» a devolver lista
vacía—. Un umbral obligaría a pelear con eso en vez de leer los sobrevivientes que sí
importan. Por eso este script sale siempre con código 0: informa, no reprueba.

Lee el XML que PIT ya produjo, así que no hay un segundo cálculo que pueda diferir del
informe HTML.

Uso:  python3 tests/mutation.py <directorio-del-proyecto> [nombre]
"""

import collections
import pathlib
import sys
import xml.etree.ElementTree as ET

# PIT considera detectado tanto al mutante que hizo fallar un test como al que colgó el
# proceso: en los dos casos la suite notó la diferencia.
DETECTADOS = ('KILLED', 'TIMED_OUT')

# El nombre del mutador no le dice nada a nadie; lo que importa es qué le hizo al código.
ROTULOS = {
    'NegateConditionalsMutator': 'invirtió la condición',
    'ConditionalsBoundaryMutator': 'movió el borde (`<` por `<=`)',
    'VoidMethodCallMutator': 'borró la llamada',
    'EmptyObjectReturnValsMutator': 'vació el retorno',
    'BooleanTrueReturnValsMutator': 'forzó el retorno a `true`',
    'BooleanFalseReturnValsMutator': 'forzó el retorno a `false`',
    'PrimitiveReturnsMutator': 'puso el retorno en cero',
    'MathMutator': 'cambió la operación',
    'IncrementsMutator': 'cambió el incremento',
}

# Cuántos sobrevivientes se listan. Los demás están en el informe HTML.
MUESTRA = 10


def main() -> int:
    proyecto = pathlib.Path(sys.argv[1])
    nombre = sys.argv[2] if len(sys.argv) > 2 else proyecto.name
    xml = proyecto / 'build' / 'reports' / 'pitest' / 'mutations.xml'

    if not xml.exists():
        print(f'### 🧬 {nombre}\n\nPIT no dejó informe en `{xml}`.')
        return 0

    mutaciones = ET.parse(xml).getroot().findall('mutation')
    if not mutaciones:
        print(f'### 🧬 {nombre}\n\nSin mutantes generados.')
        return 0

    detectados = [m for m in mutaciones if m.get('status') in DETECTADOS]
    vivos = [m for m in mutaciones if m.get('status') not in DETECTADOS]
    score = round(len(detectados) * 100 / len(mutaciones))

    print(f'### 🧬 Mutación · {nombre}\n')
    print('| Mutantes | Detectados | Sobreviven | Score |')
    print('|---:|---:|---:|---:|')
    print(f'| {len(mutaciones)} | {len(detectados)} | {len(vivos)} | **{score} %** |')

    if not vivos:
        print('\nNingún mutante sobrevivió.')
        return 0

    print('\n<details><summary>Qué sobrevive</summary>\n')
    por_clase = collections.Counter(m.findtext('mutatedClass').split('.')[-1] for m in vivos)
    print('| Clase | Sobreviven |')
    print('|---|---:|')
    for clase, n in por_clase.most_common():
        print(f'| `{clase}` | {n} |')

    print(f'\n**Los primeros {min(MUESTRA, len(vivos))}:**\n')
    for m in vivos[:MUESTRA]:
        clase = m.findtext('mutatedClass').split('.')[-1]
        mutador = m.findtext('mutator').split('.')[-1]
        rotulo = ROTULOS.get(mutador, mutador)
        print(f'- `{clase}.{m.findtext("mutatedMethod")}:{m.findtext("lineNumber")}` — {rotulo}')

    print('\n</details>\n')
    print('> Un sobreviviente no es necesariamente un defecto: puede ser un mutante '
          '*equivalente*, que produce el mismo comportamiento y ningún test puede matar. '
          'Por eso esta medición informa y no reprueba.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
