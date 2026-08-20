/**
 * Normalización de fechas.
 *
 * Los servicios no se ponen de acuerdo: `metadata.receivedAt` del gateway es epoch en
 * milisegundos, el `timestamp` del detector de anomalías es ISO-8601 y el de la cola de fallidos
 * es otro ISO. Mezclarlos sin normalizar da 1970 o `Invalid Date`, y en silencio. Todo lo que
 * muestre una fecha pasa por acá primero.
 */
export function toMillis(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value === 'string') {
    const parsed = Date.parse(value)
    return Number.isNaN(parsed) ? null : parsed
  }
  return null
}

/**
 * Serie por minuto de los últimos `minutes`, del más viejo al más nuevo.
 *
 * `now` entra por parámetro y no sale de `Date.now()`: así la función es determinista y se testea
 * sin temporizadores falsos. Lo que cae fuera de la ventana se descarta en vez de amontonarse en
 * el primer balde, que es lo que haría un `Math.max(0, ...)`.
 */
export function bucketsPerMinute(
  millis: readonly number[],
  minutes: number,
  now: number
): number[] {
  const buckets = new Array<number>(Math.max(0, minutes)).fill(0)
  if (buckets.length === 0) return buckets
  const start = now - minutes * 60_000
  for (const t of millis) {
    if (!Number.isFinite(t) || t < start || t > now) continue
    buckets[Math.min(minutes - 1, Math.floor((t - start) / 60_000))] += 1
  }
  return buckets
}

/** «hace 3 min», para no obligar a leer una fecha completa en cada fila. */
export function relativeTo(millis: number | null, now: number): string {
  if (millis === null) return '—'
  const seconds = Math.round((now - millis) / 1000)
  if (seconds < 0) return 'recién'
  if (seconds < 60) return `hace ${seconds} s`
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `hace ${minutes} min`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `hace ${hours} h`
  return `hace ${Math.round(hours / 24)} d`
}

/** Fecha y hora locales, en formato corto. */
export function formatDateTime(millis: number | null): string {
  if (millis === null) return '—'
  return new Date(millis).toLocaleString('es-AR', {
    day:    '2-digit',
    month:  '2-digit',
    year:   'numeric',
    hour:   '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}
