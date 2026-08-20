import { useContext, useMemo, useState } from 'react'
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
import './CatalogView.css'

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
        <span className="catalog__fqn">
          <span className="mono muted">{t.namespace}.</span>
          <span className="mono catalog__name">{t.name}</span>
        </span>
      ),
    },
    {
      key:    'schema',
      header: 'Esquema',
      width:  '7rem',
      render: t => t.schemaId === null
        ? <Badge tone="warning" title="Todavía no está registrado en el Schema Registry">sin registrar</Badge>
        : <span className="mono muted">#{t.schemaId}</span>,
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
        : <span className="muted">otro grupo</span>,
    },
  ]

  return (
    <ViewState poll={poll} title="Catálogo de tipos de evento">
      {() => (
        <>
          <ScopeNote scope="catalog" />

          <div className="catalog__layout">
            <div className="card">
              <div className="card-header">
                <span className="card-title">
                  {filtered.length} de {summary.total} tipos
                </span>

                <div className="catalog__filters">
                  <input
                    className="form-input catalog__search"
                    type="search"
                    placeholder="Buscar por FQN…"
                    value={search}
                    onChange={e => setSearch(e.target.value)}
                    aria-label="Buscar tipos de evento"
                  />

                  <select
                    className="form-input catalog__select"
                    value={ns}
                    onChange={e => setNs(e.target.value)}
                    disabled={onlyMine}
                    aria-label="Filtrar por namespace"
                  >
                    <option value="">Todos los namespaces</option>
                    {namespacesOf(types).map(n => <option key={n} value={n}>{n}</option>)}
                  </select>

                  <select
                    className="form-input catalog__select"
                    value={status}
                    onChange={e => setStatus(e.target.value as typeof status)}
                    aria-label="Filtrar por estado"
                  >
                    <option value="todos">Activos y archivados</option>
                    <option value="active">Sólo activos</option>
                    <option value="archived">Sólo archivados</option>
                  </select>

                  {/* Apagado por defecto: la vista global es la principal y el recorte es una
                      decisión explícita de quien mira, no el estado inicial. */}
                  <label className="catalog__toggle">
                    <input
                      type="checkbox"
                      checked={onlyMine}
                      onChange={e => setOnlyMine(e.target.checked)}
                    />
                    Sólo {namespace || 'mi namespace'}
                  </label>
                </div>
              </div>

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
            </div>

            <aside className="card catalog__aside">
              <div className="card-header"><span className="card-title">Reparto por grupo</span></div>
              <div className="card-body">
                <BarList data={summary.byNamespace} label="Tipos de evento por namespace" />
                <p className="catalog__aside-note">
                  {summary.namespaces} namespaces · {summary.active} activos ·{' '}
                  {summary.archived} archivados · {summary.withoutSchema} sin registrar
                </p>
              </div>
            </aside>
          </div>
        </>
      )}
    </ViewState>
  )
}
