import { useMemo } from 'react'
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
import './DeadLettersView.css'

function Payload({ base64 }: { base64: string }) {
  const decoded = useMemo(() => decodePayload(base64), [base64])

  if (decoded.kind === 'json') return <JsonView value={decoded.value} />
  if (decoded.kind === 'text') return <pre className="dlq__raw">{decoded.value}</pre>
  if (decoded.kind === 'binary') {
    return (
      <p className="muted">
        Contenido binario, {formatBytes(decoded.bytes)}. Es lo esperable cuando el evento murió al
        deserializarse: lo que quedó son bytes de Avro, no texto.
      </p>
    )
  }
  return <p className="muted">El payload no es base64 válido.</p>
}

const columns: Column<DeadLetter>[] = [
  {
    key:    'timestamp',
    header: 'Fecha',
    width:  '12rem',
    render: m => <span className="mono muted">{formatDateTime(toMillis(m.timestamp))}</span>,
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
    render: m => <span className="mono">{m.originalTopic}</span>,
  },
  {
    key:    'retries',
    header: 'Reintentos',
    width:  '7rem',
    render: m => m.retryCount >= 3
      ? <Badge tone="danger" title="Agotó los tres intentos">{m.retryCount} · agotado</Badge>
      : <span className="mono muted">{m.retryCount}</span>,
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
        <>
          <ScopeNote scope="deadLetters" />

          <div className="dlq__layout">
            <div className="card">
              <div className="card-header">
                <span className="card-title">{data.returned} mensajes</span>
                <span className="muted dlq__topic mono">{data.topic}</span>
              </div>

              <DataTable
                rows={data.messages}
                columns={columns}
                rowKey={m => m.dlqId}
                expanded={m => (
                  <div className="dlq__detail">
                    <p className="dlq__error">{m.errorMessage}</p>
                    <p className="dlq__label">Payload original</p>
                    <Payload base64={m.originalPayloadBase64} />
                  </div>
                )}
                empty={
                  <EmptyState
                    title="No hay mensajes fallidos"
                    detail="Nada de tu namespace terminó en la cola de fallidos. Acá caen los eventos que no se pudieron deserializar y los webhooks que agotaron sus tres reintentos."
                  />
                }
              />
            </div>

            <aside className="card dlq__aside">
              <div className="card-header"><span className="card-title">Por motivo</span></div>
              <div className="card-body">
                {summary.total === 0
                  ? <p className="muted">Sin datos.</p>
                  : <>
                      <BarList data={summary.byReason} label="Fallos por motivo" />
                      <p className="dlq__aside-label">Por tópico</p>
                      <BarList data={summary.byTopic} label="Fallos por tópico" />
                      <p className="dlq__aside-note">
                        {summary.exhausted} de {summary.total} agotaron los reintentos
                      </p>
                    </>}
              </div>
            </aside>
          </div>
        </>
      )}
    </ViewState>
  )
}
