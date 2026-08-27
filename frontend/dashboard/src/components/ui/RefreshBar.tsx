import { Button, Group, Text } from '@mantine/core'
import { relativeTo } from '@/domain/time'
import { useNow } from '@/hooks/useNow'

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
    <Group gap="sm" wrap="nowrap">
      <Text size="sm" c="dimmed" style={{ whiteSpace: 'nowrap' }}>
        {refreshing
          ? 'Actualizando…'
          : paused
            ? 'En pausa — la pestaña está en segundo plano'
            : `Actualizado ${relativeTo(lastUpdated, now)}`}
      </Text>
      <Button variant="subtle" color="gray" size="xs" onClick={onRefresh} disabled={refreshing}>
        Actualizar
      </Button>
    </Group>
  )
}
