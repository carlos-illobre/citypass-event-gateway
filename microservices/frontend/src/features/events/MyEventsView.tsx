import { Fragment, useState } from 'react'
import { Stack, Table, Text, Badge, Code, Collapse } from '@mantine/core'
import { CodeHighlight } from '@mantine/code-highlight'
import { IconChevronDown, IconChevronRight } from '@tabler/icons-react'
import { useResource } from '@/hooks/useResource'
import { useNow } from '@/hooks/useNow'
import { gateway } from '@/api/gateway'
import { LIMITS, POLL_MS } from '@/config'
import { relativeTo, formatDateTime } from '@/domain/time'
import { truncateMiddle } from '@/domain/format'
import { shortName } from '@/domain/eventTypes'
import { ScopeNote } from '@/components/layout/ScopeNote'
import { ViewState } from '@/components/layout/ViewState'

/**
 * "Mis eventos": SÓLO los publicados por este usuario (`metadata.source == sub`), no por
 * el namespace ni por el bus. `topicsScanned` distingue "no publicaste nada" de "tu
 * namespace no tiene tipos registrados", que desde una lista vacía se ven idénticas.
 */
export function MyEventsView() {
  const now = useNow()
  const [open, setOpen] = useState<string | null>(null)
  const poll = useResource(
    (t, signal) => gateway.listMyEvents(t, LIMITS.events, signal), { intervalMs: POLL_MS.events },
  )

  return (
    <Stack gap="md">
      <ScopeNote scope="events" />
      <ViewState poll={poll} title="Mis eventos">
        {data => (
          <Stack gap="xs">
            <Text size="xs" c="dimmed">
              {data.returned} eventos · {data.topicsScanned} tópicos de tu namespace escaneados
              {data.topicsScanned === 0 && ' — tu namespace todavía no tiene tipos registrados'}
            </Text>
            <Table.ScrollContainer minWidth={600}>
              <Table striped highlightOnHover verticalSpacing="xs">
                <Table.Thead>
                  <Table.Tr><Table.Th /><Table.Th>Tipo</Table.Th><Table.Th>Cuándo</Table.Th><Table.Th>eventId</Table.Th></Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {data.events.map(ev => (
                    <Fragment key={ev.metadata.eventId}>
                      <Table.Tr style={{ cursor: 'pointer' }}
                        onClick={() => setOpen(open === ev.metadata.eventId ? null : ev.metadata.eventId)}>
                        <Table.Td w={28}>
                          {open === ev.metadata.eventId ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
                        </Table.Td>
                        <Table.Td><Badge variant="light">{shortName(ev.metadata.eventType)}</Badge></Table.Td>
                        <Table.Td title={formatDateTime(ev.metadata.receivedAt)}>{relativeTo(ev.metadata.receivedAt, now)}</Table.Td>
                        <Table.Td><Code>{truncateMiddle(ev.metadata.eventId, 20)}</Code></Table.Td>
                      </Table.Tr>
                      <Table.Tr>
                        <Table.Td colSpan={4} p={0}>
                          <Collapse expanded={open === ev.metadata.eventId}>
                            <CodeHighlight code={JSON.stringify(ev, null, 2)} language="json" mx="md" my="sm" />
                          </Collapse>
                        </Table.Td>
                      </Table.Tr>
                    </Fragment>
                  ))}
                </Table.Tbody>
              </Table>
            </Table.ScrollContainer>
            {data.events.length === 0 && <Text c="dimmed" ta="center" py="xl">Todavía no publicaste ningún evento.</Text>}
          </Stack>
        )}
      </ViewState>
    </Stack>
  )
}
