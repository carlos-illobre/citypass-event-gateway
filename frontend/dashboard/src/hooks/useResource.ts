import { useContext } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { usePolling, type Poll } from './usePolling'

type Options = {
  intervalMs?: number
  enabled?:    boolean
}

/**
 * `usePolling` con el token de la sesión ya enchufado.
 *
 * Mientras no haya token queda deshabilitado, así que al cerrar sesión los sondeos se apagan
 * solos en vez de acumular 401.
 *
 * Los recursos del detector de anomalías usan `usePolling` directo: ese servicio no pide token,
 * y que se vea en el código es mejor que enterarse por un comentario.
 */
export function useResource<T>(
  fetcher: (token: string, signal: AbortSignal) => Promise<T>,
  options: Options = {}
): Poll<T> {
  const { token } = useContext(AuthContext)
  return usePolling(signal => fetcher(token, signal), {
    ...options,
    enabled: Boolean(token) && (options.enabled ?? true),
  })
}
