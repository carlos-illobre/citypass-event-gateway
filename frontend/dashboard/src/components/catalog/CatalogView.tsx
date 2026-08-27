import { useContext, useMemo, useState } from 'react'
import { Card, Checkbox, Grid, Group, Select, Text, TextInput } from '@mantine/core'
import { gateway, type EventTypeSummary } from '@/api/gateway'
import { POLL_MS } from '@/config'
import { AuthContext } from '@/contexts/auth-context'
import { useResource } from '@/hooks/useResource'
import { filterCatalog, namespacesOf, summarizeCatalog } from '@/domain/eventTypes'
import { DataTable, type Column } from '@/components/ui/DataTable'
import { EmptyState } from '@/components/ui/EmptyState'
import { Badge } from '@/components/ui/Badge'
import { BarList } from '@/components/charts/BarList'
import { ScopeNote } from '@/components/layout/ScopeNote'
import { ViewState } from '@/components/layout/ViewState'
import { SchemaViewer } from './SchemaViewer'

export function CatalogView() {
  const { namespace } = useContext(AuthContext)
  const poll = useResource(
    (token, signal) => gateway.listEventTypes(token, undefined, signal),
    { intervalMs: POLL_MS.catalog }
  )

  const [search, setSearch]   = useState('')
  const [ns, setNs]           = useState('')
  const [status, setStatus]   = useState<'todos' | 'active' | 'archived'>('todos')
  const [onlyMine, setOnlyMine] = useState(false)

  const types = useMemo(() => poll.data ?? [], [poll.data])
  const summary = useMemo(() => summarizeCatalog(types, namespace), [types, namespace])
  const filtered = useMemo(
    () => filterCatalog(types, { search, namespace: onlyMine ? namespace : ns, status }),
    [types, search, ns, status, onlyMine, namespace]
  )

  const columns: Column<EventTypeSummary>[] = [
    {
      key:    'fqn',
      header: 'Tipo de evento',
      render: t => (
        <span style={{ wordBreak: 'break-all' }}>
          <Text span c="dimmed" ff="monospace" size="sm">{t.namespace}.</Text>
          <Text span fw={600} ff="monospace" size="sm">{t.name}</Text>
        </span>
      ),
    },
    {
      key:    'schema',
      header: 'Esquema',
      width:  '7rem',
      render: t => t.schemaId === null
        ? <Badge tone="warning" title="Todavía no está registrado en el Schema Registry">sin registrar</Badge>
        : <Text ff="monospace" c="dimmed" size="sm">#{t.schemaId}</Text>,
    },
    {
      key:    'status',
      header: 'Estado',
      width:  '8rem',
      render: t => t.status === 'archived'
        ? <Badge tone="neutral" title={t.archivedAt ?? undefined}>archivado</Badge>
        : <Badge tone="ok">activo</Badge>,
    },
    {
      key:    'owner',
      header: 'Dueño',
      width:  '7rem',
      render: t => t.namespace === namespace
        ? <Badge tone="accent">tu grupo</Badge>
        : <Text c="dimmed" size="sm">otro grupo</Text>,
    },
  ]

  return (
    <ViewState poll={poll} title="Catálogo de tipos de evento">
      {() => (
        <>
          <ScopeNote scope="catalog" />

          <Grid>
            <Grid.Col span={{ base: 12, md: 8 }}>
              <Card withBorder radius="md" padding={0}>
                <Card.Section withBorder inheritPadding py="xs" px="md">
                  <Group justify="space-between" wrap="wrap">
                    <Text fw={700} fz="sm">
                      {filtered.length} de {summary.total} tipos
                    </Text>

                    <Group gap="xs" wrap="wrap">
                      <TextInput
                        placeholder="Buscar por FQN…"
                        value={search}
                        onChange={e => setSearch(e.target.value)}
                        aria-label="Buscar tipos de evento"
                        size="xs"
                        w={224}
                      />

                      <Select
                        data={[{ value: '', label: 'Todos los namespaces' }, ...namespacesOf(types).map(n => ({ value: n, label: n }))]}
                        value={ns}
                        onChange={v => setNs(v ?? '')}
                        disabled={onlyMine}
                        aria-label="Filtrar por namespace"
                        size="xs"
                        allowDeselect={false}
                      />

                      <Select
                        data={[
                          { value: 'todos', label: 'Activos y archivados' },
                          { value: 'active', label: 'Sólo activos' },
                          { value: 'archived', label: 'Sólo archivados' },
                        ]}
                        value={status}
                        onChange={v => setStatus((v ?? 'todos') as typeof status)}
                        aria-label="Filtrar por estado"
                        size="xs"
                        allowDeselect={false}
                      />

                      {/* Apagado por defecto: la vista global es la principal y el recorte es una
                          decisión explícita de quien mira, no el estado inicial. */}
                      <Checkbox
                        label={`Sólo ${namespace || 'mi namespace'}`}
                        checked={onlyMine}
                        onChange={e => setOnlyMine(e.target.checked)}
                        size="xs"
                      />
                    </Group>
                  </Group>
                </Card.Section>

                <DataTable
                  rows={filtered}
                  columns={columns}
                  rowKey={t => t.fqn}
                  expanded={t => <SchemaViewer fqn={t.fqn} />}
                  empty={
                    types.length === 0
                      ? <EmptyState
                          title="No hay tipos de evento registrados"
                          detail={<>Ningún grupo registró todavía un tipo. Se crean con <code>POST /api/v1/event-types</code>, o desde la UI del gateway.</>}
                        />
                      : <EmptyState
                          title="Ningún tipo coincide con el filtro"
                          detail="Probá con otro texto, otro namespace o incluyendo los archivados."
                        />
                  }
                />
              </Card>
            </Grid.Col>

            <Grid.Col span={{ base: 12, md: 4 }}>
              <Card withBorder radius="md" padding={0} h="100%">
                <Card.Section withBorder inheritPadding py="xs" px="md">
                  <Text fw={700} fz="sm">Reparto por grupo</Text>
                </Card.Section>
                <Card.Section inheritPadding p="md">
                  <BarList data={summary.byNamespace} label="Tipos de evento por namespace" />
                  <Text size="xs" c="dimmed" mt="sm">
                    {summary.namespaces} namespaces · {summary.active} activos ·{' '}
                    {summary.archived} archivados · {summary.withoutSchema} sin registrar
                  </Text>
                </Card.Section>
              </Card>
            </Grid.Col>
          </Grid>
        </>
      )}
    </ViewState>
  )
}
