// Agregaciones puras. Sin dependencias: no importan API, config ni React.

export type Tally = { key: string; count: number }

/**
 * Conteo por clave, de mayor a menor.
 *
 * El desempate alfabético no es cosmético: sin él, dos claves con el mismo conteo salen en el
 * orden en que aparecieron, y como el sondeo rearma la lista cada vez, el gráfico se reordenaría
 * solo cada pocos segundos sin que haya cambiado nada.
 */
export function tallyBy<T>(items: readonly T[], keyOf: (item: T) => string): Tally[] {
  const counts = new Map<string, number>()
  for (const item of items) {
    const key = keyOf(item)
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  return [...counts]
    .map(([key, count]) => ({ key, count }))
    .sort((a, b) => b.count - a.count || a.key.localeCompare(b.key))
}

/**
 * Completa el conteo con las claves que no aparecieron, en cero.
 *
 * Para el reparto por namespace la ausencia es justamente el dato: un grupo que todavía no
 * publicó nada tiene que verse, y si sólo se cuenta lo que llegó, desaparece del gráfico.
 */
export function withZeroes(tally: readonly Tally[], keys: readonly string[]): Tally[] {
  const present = new Set(tally.map(t => t.key))
  const missing = keys.filter(k => !present.has(k)).map(key => ({ key, count: 0 }))
  return [...tally, ...missing].sort((a, b) => b.count - a.count || a.key.localeCompare(b.key))
}

/** El conteo más alto de la lista, o 0 si está vacía. Es la escala de los gráficos de barras. */
export const maxCount = (tally: readonly Tally[]): number =>
  tally.reduce((max, t) => Math.max(max, t.count), 0)
