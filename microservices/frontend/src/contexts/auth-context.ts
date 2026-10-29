import { createContext } from 'react'
import type { Session } from '@/api/auth'

export type AuthState = {
  /** El access token: lo que viaja como `Authorization: Bearer` al gateway y al dispatcher. */
  token:      string
  /** El uid de LDAP de la persona (o el `sub`, si el token no lo trae). */
  user:       string
  namespace:  string
  setSession: (session: Session) => void
  logout:     () => void
}

// En su propio módulo, separado del provider: así el archivo del provider sólo exporta el
// componente y Vite Fast Refresh no lo descarta al cambiar el contexto.
export const AuthContext = createContext<AuthState>({
  token: '', user: '', namespace: '', setSession: () => {}, logout: () => {},
})
