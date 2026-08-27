import { Group, Loader, Text, Title } from '@mantine/core'
import type { ReactNode } from 'react'
import { ErrorBanner } from '@/components/ui/ErrorBanner'
import { RefreshBar } from '@/components/ui/RefreshBar'
import type { Poll } from '@/hooks/usePolling'

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
      <Group justify="space-between" wrap="wrap" mb="md">
        <Title order={2} fz="lg" lh={1.2}>{title}</Title>
        <RefreshBar
          lastUpdated={lastUpdated}
          refreshing={refreshing}
          paused={paused}
          onRefresh={refresh}
        />
      </Group>

      {error && <ErrorBanner message={error} />}

      {loading && (
        <Group justify="center" py="xl" gap="xs">
          <Loader size="sm" />
          <Text size="sm" c="dimmed">Cargando…</Text>
        </Group>
      )}

      {data !== null && children(data)}
    </section>
  )
}
