import { useState, useMemo, useCallback, useEffect, useRef, type ReactNode } from 'react'
import { setUnauthorizedHandler } from '@/api/client'
import { auth } from '@/api/auth'
import type { Credentials } from './auth-context'
import { AuthContext } from './auth-context'

type JwtPayload = {
  sub?:       string
  namespace?: string
  exp?:       number
  [key: string]: unknown
}

function decodeJwt(token: string): JwtPayload {
  try {
    const part = token.split('.')[1]
    const base64 = part.replace(/-/g, '+').replace(/_/g, '/')
    const bytes = Uint8Array.from(atob(base64), c => c.charCodeAt(0))
    return JSON.parse(new TextDecoder().decode(bytes)) as JwtPayload
  } catch {
    return {}
  }
}

function isExpired(payload: JwtPayload): boolean {
  return typeof payload.exp === 'number' && Date.now() >= payload.exp * 1000
}

/** Cuánto antes del vencimiento se renueva, para que ninguna petición salga con un token muerto. */
const MARGEN_DE_RENOVACION_MS = 60_000

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState('')

  /**
   * Las credenciales viven en un ref y **nunca** en `localStorage`.
   *
   * Son un `client_secret`: persistirlas las dejaría legibles para cualquier script de la
   * página y sobrevivirían a cerrar la pestaña. En memoria desaparecen al recargar, que es
   * el comportamiento correcto — el precio es volver a ingresar, y es el precio justo.
   */
  const credenciales = useRef<Credentials | null>(null)

  const logout = useCallback(() => {
    credenciales.current = null
    setTokenState('')
  }, [])

  const setToken = useCallback((t: string) => {
    const payload = decodeJwt(t)
    if (isExpired(payload)) { logout(); return }
    setTokenState(t)
  }, [logout])

  const login = useCallback(async (creds: Credentials) => {
    const { token: nuevo } = await auth.login(creds)
    credenciales.current = creds
    setToken(nuevo)
  }, [setToken])

  /**
   * Renueva antes de que venza, y sólo cae en logout si la renovación falla.
   *
   * Antes acá había un `setTimeout(logout, …)`: con tokens de ocho horas nadie lo veía,
   * pero con los quince minutos que emite el servicio real habría echado a la persona en
   * medio de lo que estuviera haciendo. Que la renovación falle sí es motivo de logout:
   * significa que las credenciales dejaron de servir.
   */
  useEffect(() => {
    if (!token) return
    const payload = decodeJwt(token)
    if (typeof payload.exp !== 'number') return

    const enCuanto = Math.max(0, payload.exp * 1000 - Date.now() - MARGEN_DE_RENOVACION_MS)
    const timer = setTimeout(() => {
      const creds = credenciales.current
      if (!creds) { logout(); return }
      auth.login(creds)
        .then(({ token: nuevo }) => setToken(nuevo))
        .catch(() => logout())
    }, enCuanto)

    return () => clearTimeout(timer)
  }, [token, logout, setToken])

  // Registra el handler de 401 en el cliente HTTP
  useEffect(() => {
    setUnauthorizedHandler(logout)
  }, [logout])

  const { user, namespace } = useMemo(() => {
    if (!token) return { user: '', namespace: '' }
    const p = decodeJwt(token)
    return {
      user:      typeof p.sub === 'string' ? p.sub : '',
      namespace: typeof p.namespace === 'string' ? p.namespace : '',
    }
  }, [token])

  return (
    <AuthContext.Provider value={{ token, user, namespace, login, setToken, logout }}>
      {children}
    </AuthContext.Provider>
  )
}
