import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { setUnauthorizedHandler } from '@/api/client'
import { decodeJwt, expiresAtMs, isExpired } from '@/domain/jwt'
import { AuthContext } from './auth-context'

/**
 * Sesión del navegador.
 *
 * El token vive sólo en `useState`: **no hay `localStorage` ni cookie**. Recargar la página
 * cierra la sesión. Es deliberado, no un olvido — un token de 8 h persistido es una
 * superficie de robo mucho mayor que perder la sesión al recargar, y esta consola no es un
 * lugar donde eso duela: no hay un borrador largo que perder.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState('')

  const logout = () => setTokenState('')

  const setToken = (next: string) => {
    const claims = decodeJwt(next)
    // Un token que ya nació vencido (reloj del cliente atrasado, o un valor pegado a mano)
    // no debería ni entrar: mejor rechazarlo acá que dejar que la primera petición lo haga.
    if (isExpired(claims, Date.now())) return
    setTokenState(next)
  }

  const claims = useMemo(() => decodeJwt(token), [token])
  const user      = claims.sub ?? ''
  const namespace = claims.namespace ?? ''

  // Cierre automático cuando el JWT vence. El simulador de identidad regenera su clave RSA en
  // cada reinicio, así que un token puede quedar inválido antes de su propio `exp` — pero eso
  // lo detecta el primer 401, no este temporizador; esto sólo cubre el caso normal.
  useEffect(() => {
    if (!token) return
    const exp = expiresAtMs(claims)
    if (exp === null) return
    // Siempre por `setTimeout`, incluso con 0 ms: llamar a `logout` directamente en el
    // cuerpo del efecto dispara un render en cascada durante el commit — un token que ya
    // vino vencido cierra la sesión en la vuelta del bucle de eventos, no sincrónicamente.
    const timer = setTimeout(logout, Math.max(0, exp - Date.now()))
    return () => clearTimeout(timer)
  }, [token, claims])

  // El 401 de cualquier petición autenticada cierra la sesión desde acá, no desde cada
  // pantalla: es un solo lugar que decide qué significa "se venció", y `usePolling` ya sabe
  // cortar su ciclo cuando ve ese mismo error.
  useEffect(() => {
    setUnauthorizedHandler(logout)
  }, [])

  return (
    <AuthContext value={{ token, user, namespace, setToken, logout }}>
      {children}
    </AuthContext>
  )
}
