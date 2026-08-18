import { useState } from 'react'
import type { PublishEventResponse } from '@/api/gateway'
import { JsonView } from '@/components/ui/JsonView'
import './SentEventsPanel.css'

/**
 * Un evento publicado con éxito durante esta sesión.
 *
 * Es el envelope completo que devolvió el gateway —`metadata` + `data`—, o sea
 * exactamente lo que quedó en el tópico. Nada de esto se reconstruye del lado del
 * navegador: `payloadHash`, `schemaId`, `tokenId` e `instanceId` sólo los sabe el gateway.
 */
export type SentEvent = PublishEventResponse

/** Nombre corto del event type: `com.citypass.movilidad.BiciDevuelta` → `BiciDevuelta`. */
const shortName = (fqn: string) => fqn.slice(fqn.lastIndexOf('.') + 1)

/** Hora local de `receivedAt`, que viaja como epoch en milisegundos. */
function timeOf(receivedAt: number): string {
  const date = new Date(receivedAt)
  return Number.isNaN(date.getTime()) ? String(receivedAt) : date.toLocaleTimeString()
}

function SentItem({ event }: { event: SentEvent }) {
  const [open, setOpen] = useState(false)

  return (
    <li className="sep-item">
      <button
        type="button"
        className="sep-item-header"
        onClick={() => setOpen(v => !v)}
        aria-expanded={open}
      >
        <span className="sep-caret" aria-hidden="true">{open ? '▾' : '▸'}</span>
        <span className="sep-name">{shortName(event.metadata.eventType)}</span>
        <span className="sep-time">{timeOf(event.metadata.receivedAt)}</span>
      </button>

      {open && (
        <div className="sep-item-body">
          {/*
            * El evento entero, igual que quedó en el tópico. `metadata` arranca abierta
            * porque es lo que no se puede ver en ningún otro lado: el payload ya se vio
            * al escribirlo, la metadata la agregó el gateway.
            */}
          <JsonView value={event} collapsedByDefault={path => path === 'data'} />
        </div>
      )}
    </li>
  )
}

/**
 * Últimos eventos publicados por el usuario.
 *
 * Al abrir sesión se siembra con lo que el gateway lee del bus, y los que se publican
 * después se agregan sin volver a consultar. Deliberadamente dice «últimos» y no «todos»:
 * el gateway lee la cola de los tópicos y filtra en memoria, así que un evento más viejo
 * que esa ventana no aparece. Kafka es un log, no una base con índices.
 *
 * Los más nuevos van arriba: lo que interesa mirar es lo último que se envió.
 */
export function SentEventsPanel({ items }: { items: readonly SentEvent[] }) {
  return (
    <section className="sep card">
      <div className="sep-header card-header">
        <h3 className="card-title">Últimos enviados</h3>
        {items.length > 0 && <span className="sep-count">{items.length}</span>}
      </div>

      {items.length === 0 ? (
        <p className="sep-empty">
          Todavía no publicaste ningún evento. Acá van a aparecer los últimos que envíes,
          incluso después de volver a entrar.
        </p>
      ) : (
        <ul className="sep-items">
          {items.map(event => <SentItem key={event.metadata.eventId} event={event} />)}
        </ul>
      )}
    </section>
  )
}
