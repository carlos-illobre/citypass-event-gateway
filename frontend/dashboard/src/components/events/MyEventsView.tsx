import { Card, Group, SimpleGrid, Stack, Text } from '@mantine/core'
import { gateway, type BusEvent } from '@/api/gateway'
import { POLL_MS, config } from '@/config'
import { useResource } from '@/hooks/useResource'
import { formatDateTime, toMillis } from '@/domain/time'
import { truncateMiddle } from '@/domain/format'
import { shortName } from '@/domain/eventTypes'
import { DataTable, type Column } from '@/components/ui/DataTable'
import { EmptyState } from '@/components/ui/EmptyState'
import { JsonView } from '@/components/ui/JsonView'
import { ScopeNote } from '@/components/layout/ScopeNote'
import { ViewState } from '@/components/layout/ViewState'

const columns: Column<BusEvent>[] = [
  {
    key:    'receivedAt',
    header: 'Recibido',
    width:  '12rem',
    render: e => <Text ff="monospace" c="dimmed" size="sm">{formatDateTime(toMillis(e.metadata.receivedAt))}</Text>,
  },
  {
    key:    'eventType',
    header: 'Tipo',
    render: e => <Text ff="monospace" size="sm" title={e.metadata.eventType}>{shortName(e.metadata.eventType)}</Text>,
  },
  {
    key:    'eventId',
    header: 'ID del evento',
    width:  '11rem',
    render: e => <Text ff="monospace" c="dimmed" size="sm" title={e.metadata.eventId}>{truncateMiddle(e.metadata.eventId)}</Text>,
  },
  {
    key:    'schemaId',
    header: 'Esquema',
    width:  '6rem',
    render: e => <Text ff="monospace" c="dimmed" size="sm">#{e.metadata.schemaId}</Text>,
  },
  {
    key:    'source',
    header: 'Publicado por',
    width:  '8rem',
    render: e => <Text ff="monospace" size="sm">{e.metadata.source}</Text>,
  },
]

export function MyEventsView() {
  const poll = useResource(
    (token, signal) => gateway.listMyEvents(token, config.limits.events, signal),
    { intervalMs: POLL_MS.events }
  )

  return (
    <ViewState poll={poll} title="Mis eventos publicados">
      {data => (
        <Stack gap="md">
          <ScopeNote scope="events" />

          <Card withBorder radius="md" padding={0}>
            <Card.Section withBorder inheritPadding py="xs" px="md">
              <Group justify="space-between" wrap="wrap">
                <Text fw={700} fz="sm">{data.returned} eventos</Text>
                {/* `topicsScanned` junto al total es lo que distingue «no publiqué nada» de «mi
                    namespace todavía no tiene tipos registrados»: sin ese número, las dos
                    situaciones se ven como una tabla vacía. */}
                <Text c="dimmed" size="sm">
                  {data.topicsScanned} tópicos recorridos en tu namespace
                </Text>
              </Group>
            </Card.Section>

            <DataTable
              rows={data.events}
              columns={columns}
              rowKey={e => e.metadata.eventId}
              expanded={e => (
                <SimpleGrid cols={{ base: 1, sm: 2 }}>
                  <div>
                    <Text size="sm" fw={600} mb={4}>Sobre (metadata)</Text>
                    <JsonView value={e.metadata} />
                  </div>
                  <div>
                    <Text size="sm" fw={600} mb={4}>Contenido (data)</Text>
                    <JsonView value={e.data} />
                  </div>
                </SimpleGrid>
              )}
              empty={
                data.topicsScanned === 0
                  ? <EmptyState
                      title="Tu namespace no tiene tipos de evento registrados"
                      detail={<>No hay dónde publicar todavía. Registrá uno con <code>POST /api/v1/event-types</code>, o corré <code>node scripts/seed-demo.mjs</code> para cargar datos de demostración.</>}
                    />
                  : <EmptyState
                      title="Todavía no publicaste eventos"
                      detail={<>Hay {data.topicsScanned} tópicos en tu namespace, pero ninguno tiene eventos publicados por vos. Probá con <code>node scripts/seed-demo.mjs</code>.</>}
                    />
              }
            />
          </Card>
        </Stack>
      )}
    </ViewState>
  )
}
