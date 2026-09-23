import { useEffect, useMemo, useState } from 'react'
import { markRead, parseReadIds, readStorageKey } from '@/domain/notifications'

/**
 * Qué notificaciones ya se leyeron, recordado en `localStorage` por namespace.
 *
 * A diferencia del token (ver `AuthProvider`), esto sí se persiste: son ids de mensajes
 * fallidos, no un secreto, y perderlos al recargar haría volver a encender la campanita con
 * cosas ya vistas. Si el almacenamiento no está disponible (ventana privada, sitio bloqueado)
 * funciona igual, sólo que se olvida al recargar.
 *
 * El namespace no cambia mientras el hook está montado: cerrar sesión desmonta el shell entero.
 */
export function useReadNotifications(namespace: string) {
  const key = readStorageKey(namespace)
  const [ids, setIds] = useState<string[]>(() => {
    try {
      return parseReadIds(localStorage.getItem(key))
    } catch {
      return []
    }
  })

  useEffect(() => {
    try {
      localStorage.setItem(key, JSON.stringify(ids))
    } catch {
      // Sin almacenamiento: queda en memoria.
    }
  }, [key, ids])

  const read = useMemo(() => new Set(ids), [ids])
  const mark = (toMark: readonly string[]) => setIds(prev => markRead(prev, toMark))

  return { read, mark }
}
