import { useMemo } from 'react'
import { Card, Code, Grid, Group, Stack, Text } from '@mantine/core'
import { gateway, type DeadLetter } from '@/api/gateway'
import { POLL_MS, config } from '@/config'
import { useResource } from '@/hooks/useResource'
import { decodePayload, reasonLabel, summarizeDeadLetters } from '@/domain/deadLetters'
import { formatDateTime, toMillis } from '@/domain/time'
import { formatBytes } from '@/domain/format'
import { DataTable, type Column } from '@/components/ui/DataTable'
import { EmptyState } from '@/components/ui/EmptyState'
import { JsonView } from '@/components/ui/JsonView'
import { Badge } from '@/components/ui/Badge'
import { BarList } from '@/components/charts/BarList'
import { ScopeNote } from '@/components/layout/ScopeNote'
import { ViewState } from '@/components/layout/ViewState'

function Payload({ base64 }: { base64: string }) {
  const decoded = useMemo(() => decodePayload(base64), [base64])

  if (decoded.kind === 'json') return <JsonView value={decoded.value} />
  if (decoded.kind === 'text') return <Code block>{decoded.value}</Code>
  if (decoded.kind === 'binary') {
    return (
      <Text size="sm" c="dimmed">
        Contenido binario, {formatBytes(decoded.bytes)}. Es lo esperable cuando el evento murió al
        deserializarse: lo que quedó son bytes de Avro, no texto.
      </Text>
    )
  }
  return <Text size="sm" c="dimmed">El payload no es base64 válido.</Text>
}

const columns: Column<DeadLetter>[] = [
  {
    key:    'timestamp',
    header: 'Fecha',
    width:  '12rem',
    render: m => <Text ff="monospace" c="dimmed" size="sm">{formatDateTime(toMillis(m.timestamp))}</Text>,
  },
  {
    key:    'reason',
    header: 'Motivo',
    width:  '15rem',
    render: m => <Badge tone="danger">{reasonLabel(m.failureReason)}</Badge>,
  },
  {
    key:    'topic',
    header: 'Tópico original',
    render: m => <Text ff="monospace" size="sm">{m.originalTopic}</Text>,
  },
  {
    key:    'retries',
    header: 'Reintentos',
    width:  '7rem',
    render: m => m.retryCount >= 3
      ? <Badge tone="danger" title="Agotó los tres intentos">{m.retryCount} · agotado</Badge>
      : <Text ff="monospace" c="dimmed" size="sm">{m.retryCount}</Text>,
  },
]

export function DeadLettersView() {
  const poll = useResource(
    (token, signal) => gateway.listDeadLetters(token, config.limits.deadLetters, signal),
    { intervalMs: POLL_MS.deadLetters }
  )

  const summary = useMemo(
    () => summarizeDeadLetters(poll.data?.messages ?? []),
    [poll.data]
  )

  return (
    <ViewState poll={poll} title="Mensajes fallidos">
      {data => (
        <Stack gap="md">
          <ScopeNote scope="deadLetters" />

          <Grid>
            <Grid.Col span={{ base: 12, md: 8 }}>
              <Card withBorder radius="md" padding={0}>
                <Card.Section withBorder inheritPadding py="xs" px="md">
                  <Group justify="space-between" wrap="wrap">
                    <Text fw={700} fz="sm">{data.returned} mensajes</Text>
                    <Text c="dimmed" ff="monospace" size="sm">{data.topic}</Text>
                  </Group>
                </Card.Section>

                <DataTable
                  rows={data.messages}
                  columns={columns}
                  rowKey={m => m.dlqId}
                  expanded={m => (
                    <Stack gap="xs">
                      <Text size="sm">{m.errorMessage}</Text>
                      <Text size="sm" fw={600}>Payload original</Text>
                      <Payload base64={m.originalPayloadBase64} />
                    </Stack>
                  )}
                  empty={
                    <EmptyState
                      title="No hay mensajes fallidos"
                      detail="Nada de tu namespace terminó en la cola de fallidos. Acá caen los eventos que no se pudieron deserializar y los webhooks que agotaron sus tres reintentos."
                    />
                  }
                />
              </Card>
            </Grid.Col>

            <Grid.Col span={{ base: 12, md: 4 }}>
              <Card withBorder radius="md" padding={0} h="100%">
                <Card.Section withBorder inheritPadding py="xs" px="md">
                  <Text fw={700} fz="sm">Por motivo</Text>
                </Card.Section>
                <Card.Section inheritPadding p="md">
                  {summary.total === 0
                    ? <Text size="sm" c="dimmed">Sin datos.</Text>
                    : <>
                        <BarList data={summary.byReason} label="Fallos por motivo" />
                        <Text size="sm" fw={600} mt="md" mb="xs">Por tópico</Text>
                        <BarList data={summary.byTopic} label="Fallos por tópico" />
                        <Text size="xs" c="dimmed" mt="sm">
                          {summary.exhausted} de {summary.total} agotaron los reintentos
                        </Text>
                      </>}
                </Card.Section>
              </Card>
            </Grid.Col>
          </Grid>
        </Stack>
      )}
    </ViewState>
  )
}
