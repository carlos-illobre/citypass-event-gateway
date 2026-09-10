import type { ReactNode } from 'react'
import { Group, Stack, Title, Loader, Text, ActionIcon, Tooltip } from '@mantine/core'
import { IconRefresh, IconPlayerPause } from '@tabler/icons-react'
import type { Poll } from '@/hooks/usePolling'
import { relativeTo } from '@/domain/time'
import { useNow } from '@/hooks/useNow'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

type Props<T> = {
  poll:  Poll<T>
  title: string
  /** Se renderiza sólo cuando hay datos. El error nunca borra lo último bueno que se mostró. */
  children: (data: T) => ReactNode
}

/**
 * Encabezado, estado del sondeo y los tres caminos de una vista: cargando, error y datos.
 *
 * El error se muestra ARRIBA de los datos, no en lugar de ellos: si una consulta falla
 * después de haber traído algo, seguir mostrando lo viejo con el aviso es más útil que
 * vaciar la pantalla.
 */
export function ViewState<T>({ poll, title, children }: Props<T>) {
  const { data, error, loading, refreshing, lastUpdated, paused, refresh } = poll
  const now = useNow()

  return (
    <Stack gap="md">
      <Group justify="space-between" wrap="nowrap">
        <Title order={2} size="h3">{title}</Title>
        <Group gap="xs" wrap="nowrap">
          {paused && (
            <Tooltip label="La pestaña está oculta: no se está consultando">
              <IconPlayerPause size={16} color="var(--mantine-color-dimmed)" />
            </Tooltip>
          )}
          <Text size="xs" c="dimmed">
            {lastUpdated ? `actualizado ${relativeTo(lastUpdated, now)}` : 'sin consultar todavía'}
          </Text>
          <Tooltip label="Consultar ahora">
            <ActionIcon variant="subtle" color="gray" onClick={refresh} loading={refreshing}>
              <IconRefresh size={16} />
            </ActionIcon>
          </Tooltip>
        </Group>
      </Group>

      {error && <ProblemAlert message={error} />}

      {loading && (
        <Group gap="xs" c="dimmed">
          <Loader size="sm" />
          <Text size="sm">Cargando…</Text>
        </Group>
      )}

      {data !== null && children(data)}
    </Stack>
  )
}
