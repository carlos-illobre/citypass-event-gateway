import { relativeTo } from '@/domain/time'
import { useNow } from '@/hooks/useNow'
import './RefreshBar.css'

type Props = {
  lastUpdated: number | null
  refreshing:  boolean
  paused:      boolean
  onRefresh:   () => void
}

/**
 * Estado del sondeo, a la vista.
 *
 * Un tablero que se actualiza solo necesita decir cuándo lo hizo por última vez: sin eso, un
 * dato viejo y un dato fresco se ven igual. Y la pausa por pestaña oculta se anuncia, porque si
 * no parece que se colgó.
 */
export function RefreshBar({ lastUpdated, refreshing, paused, onRefresh }: Props) {
  // `useNow` y no `Date.now()`: el segundo es impuro y no se puede llamar durante el render.
  const now = useNow()

  return (
    <div className="refresh-bar">
      <span className="refresh-bar__state">
        {refreshing
          ? 'Actualizando…'
          : paused
            ? 'En pausa — la pestaña está en segundo plano'
            : `Actualizado ${relativeTo(lastUpdated, now)}`}
      </span>
      <button className="btn-ghost" type="button" onClick={onRefresh} disabled={refreshing}>
        Actualizar
      </button>
    </div>
  )
}
