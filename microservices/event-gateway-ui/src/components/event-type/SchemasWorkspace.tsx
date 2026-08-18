import { useContext, useEffect, useState } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { gateway, type EventTypeSchema, type EventTypeSummary } from '@/api/gateway'
import { dataRecordOf, fromAvroFields, makeField, type FieldDef } from '@/domain/avro'
import { CreateEventTypeForm } from './CreateEventTypeForm'
import { EventTypeList } from './EventTypeList'
import { BackupPanel } from './BackupPanel'
import { PreviewPanel } from './PreviewPanel'
import './SchemasWorkspace.css'

/**
 * Pantalla de schemas: lista a la izquierda, editor al centro y vista previa a la
 * derecha.
 *
 * El nombre y los campos viven acá, y no dentro del formulario, porque son el
 * insumo de las dos vistas previas: al estar en el ancestro común, cada campo que
 * se agrega se refleja en el schema y en el evento de ejemplo sin pasar por el DOM.
 *
 * Editar un event type existente usa el mismo formulario: lo único que hace «Editar» es
 * cargar sus campos actuales acá. Así, lo que se ve mientras se corrige un contrato es
 * exactamente lo mismo que se ve al crearlo, con la misma validación y la misma vista
 * previa.
 */
export function SchemasWorkspace() {
  const { token, namespace } = useContext(AuthContext)

  const [name, setName]         = useState('')
  const [fields, setFields]     = useState<FieldDef[]>([makeField()])
  const [metadata, setMetadata] = useState<EventTypeSchema | null>(null)
  const [refreshKey, setRefresh] = useState(0)
  const [editing, setEditing]   = useState<EventTypeSummary | null>(null)
  const [loadingEdit, setLoadingEdit] = useState(false)

  // La metadata la define el gateway; la vista previa la muestra tal como él la inyecta.
  useEffect(() => {
    let cancelled = false
    gateway.getMetadataSchema(token)
      .then(m => { if (!cancelled) setMetadata(m) })
      .catch(() => { /* la vista previa muestra un placeholder hasta que cargue */ })
    return () => { cancelled = true }
  }, [token])

  const stopEditing = () => {
    setEditing(null)
    setName('')
    setFields([makeField()])
  }

  /**
   * Carga los campos actuales del event type en el formulario.
   *
   * Se piden al gateway en vez de reusar lo que ya tenga la lista: el resumen no trae el
   * schema, y editar a partir de una copia parcial borraría del contrato todo lo que no
   * estuviera en ella.
   */
  const startEditing = (eventType: EventTypeSummary) => {
    setLoadingEdit(true)
    gateway.getEventTypeSchema(token, eventType.fqn)
      .then(schema => {
        const data = dataRecordOf(schema.fields)
        setEditing(eventType)
        setName(schema.name)
        setFields(data && data.fields.length > 0 ? fromAvroFields(data.fields) : [makeField()])
      })
      // Si no se pudo cargar no se entra en modo edición: es preferible a abrirlo con los
      // campos de otro, que al guardar los escribiría encima.
      .catch(() => stopEditing())
      .finally(() => setLoadingEdit(false))
  }

  return (
    <div className="ws">
      <div className="ws-list">
        <BackupPanel onRestored={() => setRefresh(k => k + 1)} />

        <EventTypeList
          key={refreshKey}
          selectedFqn={editing?.fqn ?? null}
          onEdit={startEditing}
          onDeleted={fqn => { if (editing?.fqn === fqn) stopEditing() }}
        />
      </div>

      <div className="ws-form">
        {loadingEdit ? (
          <p className="ws-loading">Cargando schema…</p>
        ) : (
          <CreateEventTypeForm
            name={name}
            fields={fields}
            onName={setName}
            onFields={setFields}
            onCreated={() => setRefresh(k => k + 1)}
            editing={editing}
            onCancelEdit={stopEditing}
          />
        )}
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
