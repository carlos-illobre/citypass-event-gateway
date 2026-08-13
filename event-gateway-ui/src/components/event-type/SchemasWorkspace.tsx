import { useContext, useEffect, useState } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { gateway, type EventTypeSchema } from '@/api/gateway'
import { makeField, type FieldDef } from '@/domain/avro'
import { CreateEventTypeForm } from './CreateEventTypeForm'
import { EventTypeList } from './EventTypeList'
import { PreviewPanel } from './PreviewPanel'
import './SchemasWorkspace.css'

/**
 * Pantalla de schemas: lista a la izquierda, editor al centro y vista previa a la
 * derecha.
 *
 * El nombre y los campos viven acá, y no dentro del formulario, porque son el
 * insumo de las dos vistas previas: al estar en el ancestro común, cada campo que
 * se agrega se refleja en el schema y en el evento de ejemplo sin pasar por el DOM.
 */
export function SchemasWorkspace() {
  const { token, namespace } = useContext(AuthContext)

  const [name, setName]         = useState('')
  const [fields, setFields]     = useState<FieldDef[]>([makeField()])
  const [metadata, setMetadata] = useState<EventTypeSchema | null>(null)
  const [refreshKey, setRefresh] = useState(0)

  // La metadata la define el gateway; la vista previa la muestra tal como él la inyecta.
  useEffect(() => {
    let cancelled = false
    gateway.getMetadataSchema(token)
      .then(m => { if (!cancelled) setMetadata(m) })
      .catch(() => { /* la vista previa muestra un placeholder hasta que cargue */ })
    return () => { cancelled = true }
  }, [token])

  return (
    <div className="ws">
      <div className="ws-list">
        <EventTypeList key={refreshKey} />
      </div>

      <div className="ws-form">
        <CreateEventTypeForm
          name={name}
          fields={fields}
          onName={setName}
          onFields={setFields}
          onCreated={() => setRefresh(k => k + 1)}
        />
      </div>

      <div className="ws-preview">
        <PreviewPanel
          namespace={namespace}
          name={name}
          fields={fields}
          metadata={metadata}
        />
      </div>
    </div>
  )
}
