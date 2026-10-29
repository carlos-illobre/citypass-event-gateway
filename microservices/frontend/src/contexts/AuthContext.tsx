import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { setUnauthorizedHandler } from '@/api/client'
import { auth, type Session } from '@/api/auth'
import { decodeJwt, displayName, isExpired, refreshInMs } from '@/domain/jwt'
import { AuthContext } from './auth-context'

const SIN_SESION: Session = { accessToken: '', refreshToken: '' }

/**
 * Sesión del navegador: el login de una persona contra login-federado (LDAP).
 *
 * Los dos tokens viven sólo en `useState`: **no hay `localStorage` ni cookie**. Recargar la
 * página cierra la sesión. Es deliberado, no un olvido — un refresh token de 8 h persistido es
 * una superficie de robo mucho mayor que volver a ingresar, y esta consola no es un lugar
 * donde eso duela: no hay un borrador largo que perder.
 *
 * Mientras la pestaña esté abierta, el access token (15 min) se renueva solo un minuto antes
 * de vencer. El refresh rota en cada canje y reusar uno ya canjeado revoca la sesión entera
 * del lado del Grupo 2, así que hay un solo canje en vuelo por vez.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSessionState] = useState<Session>(SIN_SESION)
  const { accessToken: token, refreshToken } = session

  // El refresh token vigente, para el logout: leerlo de una ref evita que `logout` cambie de
  // identidad en cada renovación y que el handler de 401 quede apuntando a uno viejo.
  const refreshRef = useRef('')
  useEffect(() => { refreshRef.current = refreshToken }, [refreshToken])

  const logout = () => {
    const pendiente = refreshRef.current
    setSessionState(SIN_SESION)
    // Sin esperar la respuesta: la sesión local se cierra igual, y un logout que falla del
    // otro lado sólo deja vivo un refresh que nadie más tiene.
    if (pendiente) auth.logout(pendiente).catch(() => {})
  }

  const setSession = (next: Session) => {
    // Un token que ya nació vencido (reloj del cliente atrasado) no debería ni entrar: mejor
    // rechazarlo acá que dejar que la primera petición lo haga.
    if (isExpired(decodeJwt(next.accessToken), Date.now())) return
    setSessionState(next)
  }

  const claims    = useMemo(() => decodeJwt(token), [token])
  const user      = displayName(claims)
  const namespace = claims.namespace ?? ''

  // Renovación. Un solo temporizador por access token: al canjear llega un par nuevo, cambia
  // `token` y se agenda el siguiente. Si el canje falla —refresh vencido, revocado, persona
  // deshabilitada en LDAP— la sesión se cierra.
  useEffect(() => {
    if (!refreshToken) return
    const espera = refreshInMs(claims, Date.now())
    if (espera === null) return
    // Siempre por `setTimeout`, incluso con 0 ms: actualizar estado en el cuerpo del efecto
    // dispara un render en cascada durante el commit.
    const timer = setTimeout(() => {
      auth.refresh(refreshToken)
        .then(setSession)
        .catch(() => setSessionState(SIN_SESION))
    }, espera)
    return () => clearTimeout(timer)
  }, [refreshToken, claims])

  // El 401 de cualquier petición autenticada cierra la sesión desde acá, no desde cada
  // pantalla: es un solo lugar que decide qué significa "se venció", y `usePolling` ya sabe
  // cortar su ciclo cuando ve ese mismo error.
  useEffect(() => {
    setUnauthorizedHandler(logout)
  }, [])

  return (
    <AuthContext value={{ token, user, namespace, setSession, logout }}>
      {children}
    </AuthContext>
  )
}
