import { createContext, useState, type ReactNode } from "react";

type AuthContextType = {
  token: string,
  setToken: (token: string) => void
}

export const AuthContext = createContext<AuthContextType>({
  token: '',
  setToken: () => {}
})

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState('')
  
  return (
    <AuthContext.Provider value={{ token, setToken}}>
      {children}
    </AuthContext.Provider>
  )
}
