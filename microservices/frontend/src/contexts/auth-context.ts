import { createContext } from 'react'

export type AuthState = {
  token:     string
  user:      string
  namespace: string
  setToken:  (token: string) => void
  logout:    () => void
}

// En su propio módulo, separado del provider: así el archivo del provider sólo exporta el
// componente y Vite Fast Refresh no lo descarta al cambiar el contexto.
export const AuthContext = createContext<AuthState>({
  token: '', user: '', namespace: '', setToken: () => {}, logout: () => {},
})
