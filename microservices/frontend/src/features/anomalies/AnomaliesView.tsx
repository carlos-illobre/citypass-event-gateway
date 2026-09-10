import { Stack, Table, Text, Badge, Paper, SimpleGrid, Progress, Group, Code } from '@mantine/core'
import { usePolling } from '@/hooks/usePolling'
import { useNow } from '@/hooks/useNow'
import { anomalies } from '@/api/anomalies'
import { bySeverity, featureRows, scoreRatio, severityOf, trainingProgress } from '@/domain/anomalies'
import { relativeTo, toMillis } from '@/domain/time'
import { ScopeNote } from '@/components/layout/ScopeNote'
import { ViewState } from '@/components/layout/ViewState'
import { LIMITS, POLL_MS } from '@/config'

const SEVERITY_COLOR = { alta: 'red', media: 'orange', baja: 'gray' } as const

function ModelStatusPanel() {
  const poll = usePolling(signal => anomalies.modelStatus(signal), { intervalMs: POLL_MS.modelStatus })
  const features = usePolling(signal => anomalies.modelFeatures(signal), { intervalMs: 3_600_000 })
  if (!poll.data) return null
  const s = poll.data

  return (
    <Paper withBorder p="md">
      <Group justify="space-between" mb="sm">
        <Text fw={600}>Estado del modelo</Text>
        <Badge color={s.is_trained ? 'teal' : 'orange'} variant="light">
          {s.is_trained ? 'entrenado' : 'acumulando datos'}
        </Badge>
      </Group>
      {!s.is_trained && (
        <>
          <Progress value={trainingProgress(s) * 100} mb={4} />
          <Text size="xs" c="dimmed" mb="sm">{s.buffer_size} / {s.min_samples_to_train} eventos para el primer entrenamiento</Text>
        </>
      )}
      <SimpleGrid cols={{ base: 2, sm: 4 }}>
        <div><Text size="xs" c="dimmed">Eventos vistos</Text><Text fw={600}>{s.total_events_seen}</Text></div>
        <div><Text size="xs" c="dimmed">Re-entrena cada</Text><Text fw={600}>{s.retrain_every_n}</Text></div>
        <div><Text size="xs" c="dimmed">Contaminación</Text><Text fw={600}>{s.contamination}</Text></div>
        <div><Text size="xs" c="dimmed">Anomalías totales</Text><Text fw={600}>{s.anomalies_detected}</Text></div>
      </SimpleGrid>
      {features.data && (
        <Stack gap={2} mt="sm">
          <Text size="xs" c="dimmed" fw={600}>Las 8 features que usa:</Text>
          {Object.entries(features.data.features).map(([k, v]) => (
            <Text key={k} size="xs" c="dimmed"><Code>{k}</Code> — {v}</Text>
          ))}
        </Stack>
      )}
    </Paper>
  )
}

/**
 * Anomalías: global, sin token — el detector escucha el bus entero y no tiene forma de
 * saber de quién es cada evento salvo lo que ya venga en `metadata`. Un score no es un
 * error: el modelo marca lo raro, no lo incorrecto.
 */
export function AnomaliesView() {
  const now = useNow()
  const poll = usePolling(signal => anomalies.list(LIMITS.anomalies, signal), { intervalMs: POLL_MS.anomalies })

  return (
    <Stack gap="md">
      <ScopeNote scope="anomalies" />
      <ModelStatusPanel />
      <ViewState poll={poll} title="Anomalías detectadas">
        {data => (
          <Table.ScrollContainer minWidth={700}>
            <Table striped highlightOnHover verticalSpacing="xs">
              <Table.Thead>
                <Table.Tr><Table.Th>Score</Table.Th><Table.Th>Tópico original</Table.Th><Table.Th>Fuente</Table.Th><Table.Th>Cuándo</Table.Th></Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {bySeverity(data.anomalies).map(a => {
                  const sev = severityOf(a.anomalyScore)
                  return (
                    <Table.Tr key={a.eventId}>
                      <Table.Td>
                        <Group gap={6} wrap="nowrap">
                          <Badge color={SEVERITY_COLOR[sev]} variant="light" size="sm">{sev}</Badge>
                          <Progress value={scoreRatio(a.anomalyScore) * 100} color={SEVERITY_COLOR[sev]} w={70} size="sm" />
                          <Text size="xs" c="dimmed">{a.anomalyScore.toFixed(3)}</Text>
                        </Group>
                      </Table.Td>
                      <Table.Td><Code>{a.originalTopic}</Code></Table.Td>
                      <Table.Td>{a.originalSource}</Table.Td>
                      <Table.Td title={a.timestamp}>{relativeTo(toMillis(a.timestamp), now)}</Table.Td>
                    </Table.Tr>
                  )
                })}
              </Table.Tbody>
            </Table>
            {data.anomalies.length === 0 && <Text c="dimmed" ta="center" py="xl">Sin anomalías detectadas todavía.</Text>}
            {data.anomalies.length > 0 && (
              <Text size="xs" c="dimmed" mt="xs">
                Rasgos de la más reciente: {featureRows(data.anomalies[0].features).map(f => `${f.label} ${f.value}`).join(' · ')}
              </Text>
            )}
          </Table.ScrollContainer>
        )}
      </ViewState>
    </Stack>
  )
}
