import { useContext, useState } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { auth } from '@/api/auth'
import { ErrorBanner } from '@/components/ui/ErrorBanner'
import { ThemeToggle } from '@/components/ui/ThemeToggle'
import './LoginForm.css'

export function LoginForm() {
  const { setToken } = useContext(AuthContext)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError]       = useState('')
  const [loading, setLoading]   = useState(false)

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    auth.login({ username, password })
      .then(({ token }) => setToken(token))
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false))
  }

  return (
    <main className="login-page">
      <div className="login-card">
        <div className="login-theme-toggle"><ThemeToggle /></div>
        <h1 className="login-title">Consola del bus</h1>
        <p className="login-subtitle">CityPass+ · Event Driven Architecture</p>

        <form onSubmit={handleSubmit} className="login-form">
          <label className="login-label">
            Usuario
            <input
              className="form-input"
              type="text"
              value={username}
              onChange={e => setUsername(e.target.value)}
              autoComplete="username"
              required
            />
          </label>

          <label className="login-label">
            Contraseña
            <input
              className="form-input"
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </label>

          {error && <ErrorBanner message={error} />}

          <button className="btn-primary login-submit" type="submit" disabled={loading}>
            {loading ? 'Ingresando…' : 'Ingresar'}
          </button>
        </form>

        {/* El grupo 1 todavía no tiene cliente propio en el simulador de identidad: la lista va
            de `grupo2` a `grupo8`. Mientras tanto entramos con el namespace de analítica, que es
            el que mejor le calza a un tablero. */}
        <p className="login-hint">
          El grupo 1 no tiene cliente propio todavía. Usá <code>grupo8</code> / <code>grupo8</code>.
        </p>
      </div>
    </main>
  )
}
