import { useMemo, useState } from 'react'
import { Stack, Table, Badge, Code, Text, Group, SimpleGrid, Paper, CopyButton, Button, Collapse } from '@mantine/core'
import { CodeHighlight } from '@mantine/code-highlight'
import { IconCopy, IconCheck, IconChevronDown, IconChevronRight } from '@tabler/icons-react'
import { useResource } from '@/hooks/useResource'
import { useNow } from '@/hooks/useNow'
import { deadLetters, type DeadLetter } from '@/api/deadLetters'
import { decodePayload, reasonLabel, summarizeDeadLetters } from '@/domain/deadLetters'
import { relativeTo, toMillis } from '@/domain/time'
import { ScopeNote } from '@/components/layout/ScopeNote'
import { ViewState } from '@/components/layout/ViewState'
import { LIMITS, POLL_MS } from '@/config'

function asCurl(m: DeadLetter): string {
  const decoded = decodePayload(m.originalPayloadBase64)
  const body = decoded.kind === 'json' ? JSON.stringify(decoded.value)
    : decoded.kind === 'text' ? decoded.value
    : `# binario, ${decoded.kind === 'binary' ? decoded.bytes : 0} bytes — no se puede reenviar como texto`
  return `curl -X POST "https://TU-GATEWAY/api/v1/event-types/${m.originalTopic}/events" \\\n` +
    `  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \\\n` +
    `  -d '${body}'`
}

function Row({ m, now }: { m: DeadLetter; now: number }) {
  const [open, setOpen] = useState(false)
  const decoded = decodePayload(m.originalPayloadBase64)

  return (
    <>
      <Table.Tr style={{ cursor: 'pointer' }} onClick={() => setOpen(v => !v)}>
        <Table.Td w={28}>{open ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}</Table.Td>
        <Table.Td>
          <Badge color={m.failureReason === 'DESERIALIZATION_ERROR' ? 'red' : 'orange'} variant="light">
            {reasonLabel(m.failureReason)}
          </Badge>
        </Table.Td>
        <Table.Td><Code>{m.originalTopic}</Code></Table.Td>
        <Table.Td>{m.retryCount}</Table.Td>
        <Table.Td title={m.timestamp}>{relativeTo(toMillis(m.timestamp), now)}</Table.Td>
      </Table.Tr>
      <Table.Tr>
        <Table.Td colSpan={5} p={0}>
          <Collapse expanded={open}>
            <Stack gap="xs" mx="md" my="sm">
              <Text size="xs" c="dimmed">{m.errorMessage}</Text>
              {decoded.kind === 'json' && <CodeHighlight code={JSON.stringify(decoded.value, null, 2)} language="json" />}
              {decoded.kind === 'text' && <CodeHighlight code={decoded.value} language="text" />}
              {decoded.kind === 'binary' && <Text size="sm" c="dimmed">Payload binario ({decoded.bytes} bytes) — es Avro sin schema, no se puede mostrar como texto.</Text>}
              {decoded.kind === 'invalid' && <Text size="sm" c="red">El base64 no se pudo decodificar.</Text>}
              <Group justify="flex-end">
                <CopyButton value={asCurl(m)}>
                  {({ copied, copy }) => (
                    <Button size="xs" variant="light" leftSection={copied ? <IconCheck size={14} /> : <IconCopy size={14} />} onClick={copy}>
                      {copied ? 'Copiado' : 'Copiar como curl'}
                    </Button>
                  )}
                </CopyButton>
              </Group>
            </Stack>
          </Collapse>
        </Table.Td>
      </Table.Tr>
    </>
  )
}

/**
 * La cola de fallidos. No hay endpoint de reintento: el gateway sólo la expone para leer,
 * así que la recuperación es manual — de ahí "copiar como curl" en vez de un botón
 * "reintentar" que no existe.
 */
export function DeadLettersView() {
  const now = useNow()
  const poll = useResource(
    (t, signal) => deadLetters.list(t, LIMITS.deadLetters, signal), { intervalMs: POLL_MS.deadLetters },
  )
  const summary = useMemo(() => poll.data ? summarizeDeadLetters(poll.data.messages) : null, [poll.data])

  return (
    <Stack gap="md">
      <ScopeNote scope="deadLetters" />
      <ViewState poll={poll} title="Fallidos (DLQ)">
        {data => (
          <Stack gap="md">
            {summary && (
              <SimpleGrid cols={{ base: 2, sm: 3 }}>
                <Paper withBorder p="sm"><Text size="xs" c="dimmed">Total</Text><Text fw={700} size="lg">{summary.total}</Text></Paper>
                <Paper withBorder p="sm"><Text size="xs" c="dimmed">Reintentos agotados</Text><Text fw={700} size="lg" c={summary.exhausted > 0 ? 'red' : undefined}>{summary.exhausted}</Text></Paper>
                <Paper withBorder p="sm"><Text size="xs" c="dimmed">Tópico</Text><Code>{data.topic}</Code></Paper>
              </SimpleGrid>
            )}
            <Table.ScrollContainer minWidth={600}>
              <Table striped highlightOnHover verticalSpacing="xs">
                <Table.Thead>
                  <Table.Tr><Table.Th /><Table.Th>Motivo</Table.Th><Table.Th>Tópico</Table.Th><Table.Th>Reintentos</Table.Th><Table.Th>Cuándo</Table.Th></Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {data.messages.filter((m): m is DeadLetter => typeof m === 'object').map(m => <Row key={m.dlqId} m={m} now={now} />)}
                </Table.Tbody>
              </Table>
            </Table.ScrollContainer>
            {data.messages.length === 0 && <Text c="dimmed" ta="center" py="xl">Sin mensajes fallidos. Buena señal.</Text>}
          </Stack>
        )}
      </ViewState>
    </Stack>
  )
}
