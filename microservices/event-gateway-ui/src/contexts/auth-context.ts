import { createContext } from 'react'

/**
 * El contexto vive en su propio módulo, separado de `AuthProvider`.
 *
 * Un archivo que exporta a la vez un componente y un valor común rompe el Fast
 * Refresh de Vite: al editarlo, React no puede preservar el estado del árbol.
 */

export type AuthContextType = {
  token:     string
  user:      string
  namespace: string
  setToken:  (token: string) => void
  logout:    () => void
}

export const AuthContext = createContext<AuthContextType>({
  token:     '',
  user:      '',
  namespace: '',
  setToken:  () => {},
  logout:    () => {},
})
