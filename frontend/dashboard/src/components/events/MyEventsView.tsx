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
import './MyEventsView.css'

const columns: Column<BusEvent>[] = [
  {
    key:    'receivedAt',
    header: 'Recibido',
    width:  '12rem',
    render: e => <span className="mono muted">{formatDateTime(toMillis(e.metadata.receivedAt))}</span>,
  },
  {
    key:    'eventType',
    header: 'Tipo',
    render: e => <span className="mono events__type" title={e.metadata.eventType}>{shortName(e.metadata.eventType)}</span>,
  },
  {
    key:    'eventId',
    header: 'ID del evento',
    width:  '11rem',
    render: e => <span className="mono muted" title={e.metadata.eventId}>{truncateMiddle(e.metadata.eventId)}</span>,
  },
  {
    key:    'schemaId',
    header: 'Esquema',
    width:  '6rem',
    render: e => <span className="mono muted">#{e.metadata.schemaId}</span>,
  },
  {
    key:    'source',
    header: 'Publicado por',
    width:  '8rem',
    render: e => <span className="mono">{e.metadata.source}</span>,
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
        <>
          <ScopeNote scope="events" />

          <div className="card">
            <div className="card-header">
              <span className="card-title">{data.returned} eventos</span>
              {/* `topicsScanned` junto al total es lo que distingue «no publiqué nada» de «mi
                  namespace todavía no tiene tipos registrados»: sin ese número, las dos
                  situaciones se ven como una tabla vacía. */}
              <span className="muted events__scanned">
                {data.topicsScanned} tópicos recorridos en tu namespace
              </span>
            </div>

            <DataTable
              rows={data.events}
              columns={columns}
              rowKey={e => e.metadata.eventId}
              expanded={e => (
                <div className="events__detail">
                  <div>
                    <p className="events__label">Sobre (metadata)</p>
                    <JsonView value={e.metadata} />
                  </div>
                  <div>
                    <p className="events__label">Contenido (data)</p>
                    <JsonView value={e.data} />
                  </div>
                </div>
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
          </div>
        </>
      )}
    </ViewState>
  )
}
