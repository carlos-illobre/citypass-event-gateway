import { useContext, useEffect, useState } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { gateway } from '@/api/gateway'
import { LoginForm } from '@/components/auth/LoginForm'
import { SchemasWorkspace } from '@/components/event-type/SchemasWorkspace'
import { PublishWorkspace } from '@/components/event/PublishWorkspace'
import type { SentEvent } from '@/components/event/SentEventsPanel'
import './App.css'

type Tab = 'schemas' | 'publish'

function Dashboard() {
  const { logout, user, namespace, token } = useContext(AuthContext)
  const [tab, setTab] = useState<Tab>('schemas')

  /**
   * Eventos publicados en esta sesión.
   *
   * Vive acá arriba y no en la pantalla de publicar porque cambiar de pestaña desmonta
   * el workspace: si el estado estuviera adentro, volver a «Publicar» mostraría la lista
   * vacía. `Dashboard` sólo existe mientras hay token, así que cerrar sesión —o que el
   * token expire, o que el gateway conteste 401— lo desmonta y la lista desaparece sola,
   * sin código que la limpie.
   *
   * Al abrir sesión se siembra con lo que el gateway recuerda del bus, así que ya no se
   * pierde todo al cerrar sesión — pero no es el historial completo: son los últimos N,
   * porque Kafka es un log y no permite buscar por campo.
   */
  const [sent, setSent] = useState<readonly SentEvent[]>([])

  // Una sola vez por sesión: los eventos que se publiquen después se agregan localmente,
  // sin volver a preguntar. El fallo se ignora a propósito — no poder recuperar el
  // historial no es motivo para bloquear la pantalla de publicar.
  useEffect(() => {
    let cancelado = false
    gateway.listMyEvents(token)
      .then(r => { if (!cancelado) setSent(r.events) })
      .catch(() => {})
    return () => { cancelado = true }
  }, [token])

  return (
    <div className="dashboard">
      <header className="dashboard-header">
        <span className="dashboard-brand">Event Gateway</span>
        <nav className="dashboard-tabs">
          <button
            className={`dashboard-tab${tab === 'schemas' ? ' dashboard-tab--active' : ''}`}
            onClick={() => setTab('schemas')}
          >
            Schemas
          </button>
          <button
            className={`dashboard-tab${tab === 'publish' ? ' dashboard-tab--active' : ''}`}
            onClick={() => setTab('publish')}
          >
            Publicar
          </button>
        </nav>
        <div className="dashboard-user">
          <span className="dashboard-user-item">
            <span className="dashboard-user-label">usuario</span>
            <span className="dashboard-user-value">{user}</span>
          </span>
          <span className="dashboard-user-sep" aria-hidden="true" />
          <span className="dashboard-user-item">
            <span className="dashboard-user-label">namespace</span>
            <span className="dashboard-user-value dashboard-user-ns">{namespace}</span>
          </span>
        </div>
        <button className="dashboard-logout" onClick={logout}>Cerrar sesión</button>
      </header>

      <main className="dashboard-main dashboard-main--wide">
        {tab === 'schemas' && <SchemasWorkspace />}
        {tab === 'publish' && (
          <PublishWorkspace
            sent={sent}
            onPublished={event => setSent(prev => [event, ...prev])}
          />
        )}
      </main>
    </div>
  )
}

export default function App() {
  const { token } = useContext(AuthContext)
  return token ? <Dashboard /> : <LoginForm />
}
