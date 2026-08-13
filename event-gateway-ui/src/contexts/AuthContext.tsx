import { useState, useMemo, useCallback, useEffect, type ReactNode } from 'react'
import { setUnauthorizedHandler } from '@/api/client'
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

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState('')

  const logout = useCallback(() => setTokenState(''), [])

  const setToken = useCallback((t: string) => {
    const payload = decodeJwt(t)
    if (isExpired(payload)) { logout(); return }
    setTokenState(t)
  }, [logout])

  // Logout automático al expirar el JWT
  useEffect(() => {
    if (!token) return
    const payload = decodeJwt(token)
    if (typeof payload.exp !== 'number') return
    // Si ya expiró, el delay negativo dispara en el próximo tick. Programarlo en vez
    // de llamar a logout() acá evita un setState sincrónico dentro del efecto.
    const timer = setTimeout(logout, Math.max(0, payload.exp * 1000 - Date.now()))
    return () => clearTimeout(timer)
  }, [token, logout])

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
    <AuthContext.Provider value={{ token, user, namespace, setToken, logout }}>
      {children}
    </AuthContext.Provider>
  )
}
