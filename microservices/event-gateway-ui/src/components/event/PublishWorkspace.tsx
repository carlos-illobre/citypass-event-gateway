import { useState } from 'react'
import type { EventTypeSummary } from '@/api/gateway'
import { EventTypeList } from '@/components/event-type/EventTypeList'
import { PublishEventForm } from './PublishEventForm'
import type { SentEvent } from './SentEventsPanel'
import './PublishWorkspace.css'

type Props = {
  /**
   * Historial de la sesión. Llega desde el Dashboard y no se guarda acá: cambiar de
   * pestaña desmonta este componente, así que un estado local se perdería al volver.
   */
  sent: readonly SentEvent[]
  onPublished: (event: SentEvent) => void
}

/**
 * Pantalla de publicación: lista a la izquierda, formulario al centro y el payload
 * que se va a enviar a la derecha.
 *
 * Reutiliza la misma lista que la pantalla de schemas, en modo selector: se puede
 * elegir un event type y, sin perder la selección, desplegar su schema para
 * consultar los tipos mientras se completan los campos. Editar y borrar se ocultan
 * porque acá la lista sirve para elegir, no para administrar.
 */
export function PublishWorkspace({ sent, onPublished }: Props) {
  const [selected, setSelected] = useState<EventTypeSummary | null>(null)
  const fqn = selected?.fqn ?? ''

  return (
    <div className="pw">
      <div className="pw-list">
        <EventTypeList selectedFqn={fqn} onSelect={setSelected} manageable={false} />
      </div>

      <PublishEventForm
        key={fqn}
        fqn={fqn}
        topic={selected?.topic ?? ''}
        sent={sent}
        onPublished={onPublished}
      />
    </div>
  )
}
