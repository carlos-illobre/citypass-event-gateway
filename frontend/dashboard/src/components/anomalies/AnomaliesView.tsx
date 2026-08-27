import { useMemo } from 'react'
import { Card, Group, Stack, Table, Text } from '@mantine/core'
import { anomaly, type Anomaly } from '@/api/anomalies'
import { POLL_MS, config } from '@/config'
import { usePolling } from '@/hooks/usePolling'
import { bySeverity, featureRows, severityOf, tallyByTopic } from '@/domain/anomalies'
import { formatDateTime, toMillis } from '@/domain/time'
import { DataTable, type Column } from '@/components/ui/DataTable'
import { EmptyState } from '@/components/ui/EmptyState'
import { Badge } from '@/components/ui/Badge'
import { BarList } from '@/components/charts/BarList'
import { ScoreBar } from '@/components/charts/ScoreBar'
import { ScopeNote } from '@/components/layout/ScopeNote'
import { ViewState } from '@/components/layout/ViewState'
import { ModelStatusPanel } from './ModelStatusPanel'

const TONE = { alta: 'danger', media: 'warning', baja: 'neutral' } as const

const columns: Column<Anomaly>[] = [
  {
    key:    'timestamp',
    header: 'Detectada',
    width:  '12rem',
    render: a => <Text ff="monospace" c="dimmed" size="sm">{formatDateTime(toMillis(a.timestamp))}</Text>,
  },
  {
    key:    'severity',
    header: 'Severidad',
    width:  '7rem',
    render: a => <Badge tone={TONE[severityOf(a.anomalyScore)]}>{severityOf(a.anomalyScore)}</Badge>,
  },
  {
    key:    'score',
    header: 'Score',
    width:  '12rem',
    render: a => <ScoreBar score={a.anomalyScore} />,
  },
  {
    key:    'topic',
    header: 'Tópico original',
    render: a => <Text ff="monospace" size="sm">{a.originalTopic}</Text>,
  },
  {
    key:    'source',
    header: 'Publicado por',
    width:  '8rem',
    render: a => <Text ff="monospace" c="dimmed" size="sm">{a.originalSource}</Text>,
  },
]

export function AnomaliesView() {
  // `usePolling` y no `useResource`: el detector no pide token. Que se vea acá es mejor que
  // enterarse por un comentario tres archivos más adentro.
  const listPoll = usePolling(
    signal => anomaly.list(config.limits.anomalies, signal),
    { intervalMs: POLL_MS.anomalies }
  )
  const statusPoll = usePolling(
    signal => anomaly.modelStatus(signal),
    { intervalMs: POLL_MS.modelStatus }
  )

  const ordered = useMemo(() => bySeverity(listPoll.data?.anomalies ?? []), [listPoll.data])
  const byTopic = useMemo(() => tallyByTopic(ordered), [ordered])

  return (
    <ViewState poll={listPoll} title="Anomalías detectadas">
      {data => (
        <Stack gap="md">
          <ScopeNote scope="anomalies" />

          <Group align="flex-start" wrap="wrap" gap="md">
            <Card withBorder radius="md" padding={0} style={{ flex: '2 1 32rem' }}>
              <Card.Section withBorder inheritPadding py="xs" px="md">
                <Group justify="space-between" wrap="wrap">
                  <Text fw={700} fz="sm">{data.returned} de {data.total} anomalías</Text>
                  <Text c="dimmed" size="sm">
                    el score es negativo: más negativo, más raro
                  </Text>
                </Group>
              </Card.Section>

              <DataTable
                rows={ordered}
                columns={columns}
                rowKey={a => a.eventId}
                expanded={a => (
                  <Stack gap="xs">
                    <Text size="sm" fw={600}>Rasgos que evaluó el modelo</Text>
                    <Table withRowBorders={false}>
                      <Table.Tbody>
                        {featureRows(a.features).map(({ key, label, value }) => (
                          <Table.Tr key={key}>
                            <Table.Th>{label}</Table.Th>
                            <Table.Td><Text ff="monospace" size="sm">{value}</Text></Table.Td>
                          </Table.Tr>
                        ))}
                      </Table.Tbody>
                    </Table>
                    <Text size="sm" c="dimmed">
                      Evento original <Text span ff="monospace">{a.originalEventId}</Text>, publicado
                      por <Text span ff="monospace">{a.originalSource}</Text>.
                    </Text>
                  </Stack>
                )}
                empty={
                  statusPoll.data && !statusPoll.data.is_trained
                    ? <EmptyState
                        title="El modelo todavía no entrenó"
                        detail={<>Lleva {statusPoll.data.buffer_size} de {statusPoll.data.min_samples_to_train} muestras. Hasta llegar a esa cantidad no marca nada — la tabla vacía no significa que el bus esté tranquilo.</>}
                      />
                    : <EmptyState
                        title="No hay anomalías registradas"
                        detail="El modelo está entrenado y no marcó nada. Tené en cuenta que la lista vive en memoria: reiniciar el detector la vacía."
                      />
                }
              />
            </Card>

            <Stack gap="md" style={{ flex: '1 1 18rem' }}>
              {statusPoll.data && <ModelStatusPanel status={statusPoll.data} />}

              {byTopic.length > 0 && (
                <Card withBorder radius="md" padding={0}>
                  <Card.Section withBorder inheritPadding py="xs" px="md">
                    <Text fw={700} fz="sm">Por tópico</Text>
                  </Card.Section>
                  <Card.Section inheritPadding p="md">
                    <BarList data={byTopic} label="Anomalías por tópico" />
                  </Card.Section>
                </Card>
              )}
            </Stack>
          </Group>
        </Stack>
      )}
    </ViewState>
  )
}
