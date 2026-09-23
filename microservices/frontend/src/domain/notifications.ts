import type { DeadLetter } from '@/api/deadLetters'

/** Cuántos fallidos trae la campanita: alcanza para "lo último", no es la vista de la DLQ. */
export const NOTIFICATIONS_LIMIT = 20

/**
 * Tope de ids leídos que se guardan. La DLQ no tiene fin y sin esto el registro de leídos
 * crecería para siempre; con 500 sobra, porque la campanita nunca muestra más de 20.
 */
export const READ_CAP = 500

export function unreadOf(messages: readonly DeadLetter[], read: ReadonlySet<string>): DeadLetter[] {
  return messages.filter(m => !read.has(m.dlqId))
}

/**
 * Agrega `ids` a los leídos, sin duplicar, y recorta por el lado más viejo: los recién
 * marcados quedan al final, así que son los últimos en caerse del tope.
 */
export function markRead(read: readonly string[], ids: readonly string[], cap = READ_CAP): string[] {
  const fresh = ids.filter(id => !read.includes(id))
  if (fresh.length === 0) return [...read]
  const next = [...read, ...new Set(fresh)]
  return next.slice(Math.max(0, next.length - cap))
}

/**
 * Texto de la insignia. Si la página vino llena y todo está sin leer, puede haber más de los
 * que trajimos: «20+» dice la verdad, «20» no.
 */
export function badgeLabel(unread: number, returned: number, limit = NOTIFICATIONS_LIMIT): string {
  return unread >= limit && returned >= limit ? `${limit}+` : String(unread)
}

/** Clave de `localStorage` por namespace: dos equipos en el mismo navegador no se pisan. */
export const readStorageKey = (namespace: string) => `citypass.notifications.read.${namespace}`

/** Lectura tolerante: cualquier cosa que no sea una lista de strings cuenta como "nada leído". */
export function parseReadIds(raw: string | null): string[] {
  if (!raw) return []
  try {
    const value: unknown = JSON.parse(raw)
    return Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : []
  } catch {
    return []
  }
}
