#!/usr/bin/env python3
"""
Resume la cobertura y los tests de un proyecto Gradle, en Markdown.

Lo usa el pipeline para escribir en `$GITHUB_STEP_SUMMARY`, de modo que cada ejecución
muestre los números arriba de todo sin que nadie los mantenga a mano. Los de
`docs/TESTING.md` se habían desfasado justamente por eso.

Lee lo que el build ya produce —el XML de JaCoCo y los resultados JUnit— así que no hay
un segundo cálculo que pueda diferir del que hace fallar la compuerta.

Uso:  python3 tests/coverage.py <directorio-del-proyecto> [nombre]
"""

import glob
import os
import re
import sys

# Las dos que exige el umbral. El resto son consecuencia y sólo agregan ruido.
METRICAS = [('INSTRUCTION', 'instrucciones'), ('BRANCH', 'ramas')]


def cobertura(proyecto: str) -> dict[str, tuple[int, int]]:
    """Cubiertas y totales por métrica, del total del reporte."""
    ruta = os.path.join(proyecto, 'build/reports/jacoco/test/jacocoTestReport.xml')
    if not os.path.exists(ruta):
        return {}
    contenido = open(ruta).read()
    # Los contadores del final son los del reporte entero; los de antes son por paquete.
    totales = contenido[contenido.rfind('</package>'):]
    return {
        m.group(1): (int(m.group(3)), int(m.group(2)) + int(m.group(3)))
        for m in re.finditer(r'<counter type="(\w+)" missed="(\d+)" covered="(\d+)"/>', totales)
    }


def tests(proyecto: str) -> dict[str, int]:
    """Cantidad de tests por tarea (test, integrationTest, …)."""
    conteo = {}
    for directorio in sorted(glob.glob(os.path.join(proyecto, 'build/test-results/*'))):
        if not os.path.isdir(directorio):
            continue
        total = 0
        for archivo in glob.glob(os.path.join(directorio, '*.xml')):
            encontrado = re.search(r'tests="(\d+)"', open(archivo).read())
            if encontrado:
                total += int(encontrado.group(1))
        if total:
            conteo[os.path.basename(directorio)] = total
    return conteo


def main() -> None:
    proyecto = sys.argv[1]
    nombre = sys.argv[2] if len(sys.argv) > 2 else os.path.basename(proyecto.rstrip('/'))

    datos = cobertura(proyecto)
    if not datos:
        print(f'### {nombre}\n\nNo se encontró el reporte de JaCoCo.')
        return

    print(f'### {nombre}\n')
    print('| Métrica | Cobertura | |')
    print('|---|---|---|')
    for clave, etiqueta in METRICAS:
        if clave not in datos:
            continue
        cubierto, total = datos[clave]
        porcentaje = cubierto / total if total else 1.0
        # El umbral de este repositorio es 100 %: cualquier cosa menor es un fallo, no
        # un número más bajo.
        print(f'| {etiqueta} | {porcentaje:.1%} | {cubierto}/{total} {"✅" if cubierto == total else "❌"} |')

    conteo = tests(proyecto)
    if conteo:
        detalle = ', '.join(f'{v} {k}' for k, v in conteo.items())
        print(f'\n**{sum(conteo.values())} tests** — {detalle}\n')


if __name__ == '__main__':
    main()
