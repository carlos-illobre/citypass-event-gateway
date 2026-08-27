import { useContext, useState, type ReactNode } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { ThemeToggle } from '@/components/ui/ThemeToggle'
import { TABS, type TabId } from './tabs'
import './AppShell.css'

type Props = {
  tab:      TabId
  onTab:    (tab: TabId) => void
  children: ReactNode
}

export function AppShell({ tab, onTab, children }: Props) {
  const { user, namespace, logout } = useContext(AuthContext)
  const [collapsed, setCollapsed] = useState(false)

  return (
    <div className={`shell${collapsed ? ' shell--collapsed' : ''}`}>
      <aside className="shell__sidebar">
        <div className="shell__sidebar-head">
          <div className="shell__brand">
            <span className="shell__title">Consola del bus</span>
            <span className="shell__subtitle">CityPass+ · EDA</span>
          </div>
          <button
            type="button"
            className="shell__toggle"
            onClick={() => setCollapsed(c => !c)}
            aria-label={collapsed ? 'Mostrar barra lateral' : 'Ocultar barra lateral'}
            title={collapsed ? 'Mostrar barra lateral' : 'Ocultar barra lateral'}
          >
            {collapsed ? '»' : '«'}
          </button>
        </div>

        <nav className="shell__nav" aria-label="Secciones">
          {TABS.map(({ id, label }) => (
            <button
              key={id}
              type="button"
              className={`shell__tab${tab === id ? ' shell__tab--active' : ''}`}
              aria-current={tab === id ? 'page' : undefined}
              onClick={() => onTab(id)}
            >
              {label}
            </button>
          ))}
        </nav>

        <div className="shell__session">
          <span className="shell__identity">
            <strong>{user}</strong>
            <span className="mono muted">{namespace}</span>
          </span>
          <ThemeToggle />
          <button className="btn-ghost" type="button" onClick={logout}>Salir</button>
        </div>
      </aside>

      {/* Sólo se monta la vista activa: así sólo consulta la sección que se está mirando, que es
          lo que mantiene el gasto de peticiones dentro de la cuota del namespace. */}
      <main className="shell__main">{children}</main>
    </div>
  )
}
