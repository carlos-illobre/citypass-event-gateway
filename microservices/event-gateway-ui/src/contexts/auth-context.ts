import { createContext } from 'react'

/**
 * El contexto vive en su propio módulo, separado de `AuthProvider`.
 *
 * Un archivo que exporta a la vez un componente y un valor común rompe el Fast
 * Refresh de Vite: al editarlo, React no puede preservar el estado del árbol.
 */

export type Credentials = { username: string; password: string }

export type AuthContextType = {
  token:     string
  user:      string
  namespace: string
  /**
   * Autentica y **recuerda las credenciales en memoria** para poder renovar.
   *
   * El token dura quince minutos. Sin renovación, la persona quedaría afuera cada cuarto
   * de hora en medio de lo que esté haciendo. Con `client_credentials` no hay refresh
   * token —el estándar no lo prevé para este flujo—, así que renovar es volver a pedir.
   */
  login:     (credentials: Credentials) => Promise<void>
  setToken:  (token: string) => void
  logout:    () => void
}

export const AuthContext = createContext<AuthContextType>({
  token:     '',
  user:      '',
  namespace: '',
  login:     async () => {},
  setToken:  () => {},
  logout:    () => {},
})
