import type { ReactNode } from 'react'
import { ErrorBanner } from '@/components/ui/ErrorBanner'
import { RefreshBar } from '@/components/ui/RefreshBar'
import type { Poll } from '@/hooks/usePolling'
import './ViewState.css'

type Props<T> = {
  poll:     Poll<T>
  title:    string
  /** Se renderiza sólo cuando hay datos. El error nunca borra lo último bueno que se mostró. */
  children: (data: T) => ReactNode
}

/**
 * Encabezado, estado del sondeo y los tres caminos de una vista: cargando, error y datos.
 *
 * El error se muestra ARRIBA de los datos, no en lugar de ellos: si una consulta falla después
 * de haber traído algo, seguir mostrando lo viejo con el aviso es más útil que vaciar la
 * pantalla.
 */
export function ViewState<T>({ poll, title, children }: Props<T>) {
  const { data, error, loading, refreshing, lastUpdated, paused, refresh } = poll

  return (
    <section>
      <header className="view-state__header">
        <h2 className="view-title">{title}</h2>
        <RefreshBar
          lastUpdated={lastUpdated}
          refreshing={refreshing}
          paused={paused}
          onRefresh={refresh}
        />
      </header>

      {error && <div className="view-state__error"><ErrorBanner message={error} /></div>}

      {loading && <p className="view-state__loading">Cargando…</p>}

      {data !== null && children(data)}
    </section>
  )
}
