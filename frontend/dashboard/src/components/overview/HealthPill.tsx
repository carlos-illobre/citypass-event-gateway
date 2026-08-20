import type { ServiceHealth } from '@/api/gateway'
import type { Poll } from '@/hooks/usePolling'
import './HealthPill.css'

type Props = {
  name: string
  poll: Poll<ServiceHealth>
}

/**
 * Estado de un servicio.
 *
 * Cualquier respuesta 2xx cuenta como «arriba»: el `status: "UP"` del cuerpo es informativo, y
 * atarse a ese texto haría que el indicador se rompa el día que un servicio conteste otra cosa
 * estando perfectamente vivo.
 */
export function HealthPill({ name, poll }: Props) {
  const state = poll.loading ? 'consultando' : poll.data ? 'arriba' : 'sin respuesta'
  const tone  = poll.loading ? 'loading' : poll.data ? 'up' : 'down'

  return (
    <span className={`health-pill health-pill--${tone}`}>
      <span className="health-pill__dot" aria-hidden="true" />
      <span className="mono">{name}</span>
      <span className="health-pill__state">{state}</span>
    </span>
  )
}
