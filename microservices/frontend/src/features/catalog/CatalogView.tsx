import { useContext, useMemo, useState } from 'react'
import {
  Stack, Group, TextInput, Select, Table, Badge, Text, Drawer, Loader, Code, ActionIcon,
} from '@mantine/core'
import { CodeHighlight } from '@mantine/code-highlight'
import { IconSearch, IconEye } from '@tabler/icons-react'
import { AuthContext } from '@/contexts/auth-context'
import { useResource } from '@/hooks/useResource'
import { gateway, type EventTypeSummary } from '@/api/gateway'
import { filterCatalog, namespacesOf, shortName } from '@/domain/eventTypes'
import { POLL_MS } from '@/config'
import { ScopeNote } from '@/components/layout/ScopeNote'
import { ViewState } from '@/components/layout/ViewState'

function SchemaDrawer({ fqn, onClose }: { fqn: string | null; onClose: () => void }) {
  const schema = useResource(
    (t, signal) => gateway.getEventTypeSchema(t, fqn ?? '', signal),
    { enabled: fqn !== null, intervalMs: 3_600_000 },
  )

  return (
    <Drawer opened={fqn !== null} onClose={onClose} title={fqn ? <Code>{fqn}</Code> : ''} position="right" size="lg">
      {!schema.data && <Loader size="sm" />}
      {schema.data && (
        <CodeHighlight code={JSON.stringify(schema.data, null, 2)} language="json" withCopyButton />
      )}
    </Drawer>
  )
}

/**
 * El catálogo. `GET /event-types` no está acotado por namespace por defecto: es la
 * fotografía completa de los ocho grupos, y por eso lleva su propio `ScopeNote` — la vista
 * general ya explica esto por tarjeta, pero acá es la pantalla entera la que es global.
 */
export function CatalogView() {
  const { namespace } = useContext(AuthContext)
  const [search, setSearch] = useState('')
  const [ns, setNs] = useState('')
  const [openFqn, setOpenFqn] = useState<string | null>(null)

  const poll = useResource(
    (t, signal) => gateway.listEventTypes(t, undefined, signal), { intervalMs: POLL_MS.catalog },
  )

  const filtered = useMemo(
    () => poll.data ? filterCatalog(poll.data, { search, namespace: ns }) : [],
    [poll.data, search, ns],
  )
  const namespaces = useMemo(() => poll.data ? namespacesOf(poll.data) : [], [poll.data])

  return (
    <Stack gap="md">
      <ScopeNote scope="catalog" />
      <ViewState poll={poll} title="Catálogo de tipos">
        {(types: EventTypeSummary[]) => (
          <Stack gap="md">
            <Group>
              <TextInput
                placeholder="Buscar por FQN…" leftSection={<IconSearch size={14} />}
                value={search} onChange={e => setSearch(e.target.value)} w={280}
              />
              <Select
                placeholder="Todos los namespaces" clearable data={namespaces}
                value={ns} onChange={v => setNs(v ?? '')} w={260}
              />
              <Text size="sm" c="dimmed" ml="auto">
                {filtered.length} de {types.length}{namespace && ` · ${types.filter(t => t.namespace === namespace).length} tuyos`}
              </Text>
            </Group>

            <Table.ScrollContainer minWidth={700}>
              <Table striped highlightOnHover verticalSpacing="xs">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Tipo</Table.Th>
                    <Table.Th>Namespace</Table.Th>
                    <Table.Th>Tópico actual</Table.Th>
                    <Table.Th>Versión</Table.Th>
                    <Table.Th>Schema</Table.Th>
                    <Table.Th />
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {filtered.map(t => (
                    <Table.Tr key={t.fqn}>
                      <Table.Td><Text fw={500}>{shortName(t.fqn)}</Text></Table.Td>
                      <Table.Td>
                        <Badge variant={t.namespace === namespace ? 'filled' : 'light'} color={t.namespace === namespace ? 'citypass' : 'gray'}>
                          {t.namespace}
                        </Badge>
                      </Table.Td>
                      <Table.Td><Code>{t.topic}</Code></Table.Td>
                      <Table.Td>
                        v{t.version}{t.versions.length > 1 && <Text span c="dimmed" size="xs"> ({t.versions.length} en total)</Text>}
                      </Table.Td>
                      <Table.Td>
                        {t.schemaId === null
                          ? <Badge color="orange" variant="light">pendiente</Badge>
                          : <Text size="xs" c="dimmed">#{t.schemaId}</Text>}
                      </Table.Td>
                      <Table.Td>
                        <ActionIcon variant="subtle" onClick={() => setOpenFqn(t.fqn)} aria-label="Ver schema">
                          <IconEye size={16} />
                        </ActionIcon>
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Table.ScrollContainer>

            {filtered.length === 0 && <Text c="dimmed" ta="center" py="xl">Ningún tipo coincide con el filtro.</Text>}
          </Stack>
        )}
      </ViewState>

      <SchemaDrawer fqn={openFqn} onClose={() => setOpenFqn(null)} />
    </Stack>
  )
}
