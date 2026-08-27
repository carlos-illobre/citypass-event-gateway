import { Badge, type MantineColor } from '@mantine/core'
import type { ServiceHealth } from '@/api/gateway'
import type { Poll } from '@/hooks/usePolling'

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
  const color: MantineColor = poll.loading ? 'gray' : poll.data ? 'teal' : 'red'

  return (
    <Badge variant="dot" color={color} tt="none" fw={500} size="lg">
      <span style={{ fontFamily: 'var(--mantine-font-family-monospace)' }}>{name}</span>{' '}
      {state}
    </Badge>
  )
}
