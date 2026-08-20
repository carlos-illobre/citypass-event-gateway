import { useMemo } from 'react'
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
import './AnomaliesView.css'

const TONE = { alta: 'danger', media: 'warning', baja: 'neutral' } as const

const columns: Column<Anomaly>[] = [
  {
    key:    'timestamp',
    header: 'Detectada',
    width:  '12rem',
    render: a => <span className="mono muted">{formatDateTime(toMillis(a.timestamp))}</span>,
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
    render: a => <span className="mono">{a.originalTopic}</span>,
  },
  {
    key:    'source',
    header: 'Publicado por',
    width:  '8rem',
    render: a => <span className="mono muted">{a.originalSource}</span>,
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
        <>
          <ScopeNote scope="anomalies" />

          <div className="anomalies__layout">
            <div className="card">
              <div className="card-header">
                <span className="card-title">{data.returned} de {data.total} anomalías</span>
                <span className="muted anomalies__hint">
                  el score es negativo: más negativo, más raro
                </span>
              </div>

              <DataTable
                rows={ordered}
                columns={columns}
                rowKey={a => a.eventId}
                expanded={a => (
                  <div className="anomalies__detail">
                    <p className="anomalies__label">Rasgos que evaluó el modelo</p>
                    <table className="anomalies__features">
                      <tbody>
                        {featureRows(a.features).map(({ key, label, value }) => (
                          <tr key={key}>
                            <th scope="row">{label}</th>
                            <td className="mono">{value}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    <p className="anomalies__origin muted">
                      Evento original <span className="mono">{a.originalEventId}</span>, publicado
                      por <span className="mono">{a.originalSource}</span>.
                    </p>
                  </div>
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
            </div>

            <aside className="anomalies__aside">
              {statusPoll.data && <ModelStatusPanel status={statusPoll.data} />}

              {byTopic.length > 0 && (
                <div className="card">
                  <div className="card-header"><span className="card-title">Por tópico</span></div>
                  <div className="card-body">
                    <BarList data={byTopic} label="Anomalías por tópico" />
                  </div>
                </div>
              )}
            </aside>
          </div>
        </>
      )}
    </ViewState>
  )
}
