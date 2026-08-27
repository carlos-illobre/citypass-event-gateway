/** Miles con separador local. Los contadores del tablero llegan a cinco cifras rápido. */
export const formatNumber = (value: number): string =>
  Number.isFinite(value) ? value.toLocaleString('es-AR') : '—'

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} kB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * Recorta por el medio, no por el final.
 *
 * Un `eventId` o un `payloadHash` se distinguen por las dos puntas: cortar sólo el final deja
 * una columna de valores que parecen todos iguales.
 */
export function truncateMiddle(text: string, max = 16): string {
  if (text.length <= max) return text
  const head = Math.ceil((max - 1) / 2)
  const tail = Math.floor((max - 1) / 2)
  return `${text.slice(0, head)}…${text.slice(text.length - tail)}`
}
