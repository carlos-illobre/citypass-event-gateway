import { useContext, useEffect, useState } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { gateway, type EventTypeSchema, type EventTypeSummary } from '@/api/gateway'
import { dataRecordOf, metadataRecordOf, type RawField } from '@/domain/avro'
import { ErrorBanner } from '@/components/ui/ErrorBanner'
import './EventTypeList.css'

function formatType(type: unknown): string {
  if (typeof type === 'string') return type
  if (Array.isArray(type)) {
    const nonNull = (type as unknown[]).filter(t => t !== 'null')
    const suffix = type.includes('null') ? '?' : ''
    return nonNull.map(formatType).join(' | ') + suffix
  }
  if (type !== null && typeof type === 'object') {
    const t = type as Record<string, unknown>
    if (t.type === 'array')  return `${formatType(t.items)}[]`
    if (t.type === 'record') return t.name as string
    if (t.type === 'map')    return `Map<${formatType(t.values)}>`
  }
  return JSON.stringify(type)
}

function FieldsTable({ caption, fields, muted }: {
  caption: string
  fields:  RawField[]
  muted?:  boolean
}) {
  return (
    <table className="et-fields-table">
      <caption className="et-fields-caption">{caption}</caption>
      <thead>
        <tr>
          <th>Campo</th>
          <th>Tipo</th>
        </tr>
      </thead>
      <tbody>
        {fields.map(field => (
          <tr key={field.name} className={muted ? 'et-field-base' : ''}>
            <td><code>{field.name}</code></td>
            <td><code>{formatType(field.type)}</code></td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function SchemaDetail({ schema }: { schema: EventTypeSchema }) {
  const data     = dataRecordOf(schema.fields)
  const metadata = metadataRecordOf(schema.fields)

  return (
    <div className="et-detail">
      <div className="et-detail-meta">
        <span className="et-detail-meta-item">
          <span className="et-detail-label">namespace</span>
          <code>{schema.namespace}</code>
        </span>
        <span className="et-detail-meta-item">
          <span className="et-detail-label">name</span>
          <code>{schema.name}</code>
        </span>
      </div>

      {data
        ? <FieldsTable caption="data — campos del productor" fields={data.fields} />
        : (
          <p className="et-detail-loading">
            Formato anterior (campos planos). Volvé a registrarlo para usar el envelope
            metadata/data.
          </p>
        )}

      {metadata && (
        <FieldsTable caption="metadata — la calcula el gateway" fields={metadata.fields} muted />
      )}
    </div>
  )
}

type Props = {
  /**
   * Si se pasa `onSelect`, la lista además funciona como selector: el clic sobre el
   * FQN elige el event type en vez de expandirlo. El chevron sigue expandiendo, así
   * que se puede espiar el schema sin cambiar la selección.
   */
  selectedFqn?: string | null
  /** Recibe el resumen completo, no sólo el FQN: quien elige suele necesitar el estado. */
  onSelect?:    (eventType: EventTypeSummary) => void
  /** El archivado se oculta donde la lista se usa para elegir, no para administrar. */
  archivable?:  boolean
}

export function EventTypeList({ selectedFqn = null, onSelect, archivable = true }: Props = {}) {
  const { token } = useContext(AuthContext)
  const [eventTypes, setEventTypes]       = useState<EventTypeSummary[]>([])
  const [loading, setLoading]             = useState(true)
  const [error, setError]                 = useState('')
  const [confirmArchive, setConfirmArchive] = useState<string | null>(null)
  const [expandedFqn, setExpandedFqn]     = useState<string | null>(null)
  const [schemas, setSchemas]             = useState<Record<string, EventTypeSchema>>({})
  const [schemaLoading, setSchemaLoading] = useState<string | null>(null)

  // La lista se recarga bumpeando reloadKey desde un handler, no llamando a una
  // función que hace setState dentro del efecto. `loading` ya arranca en true,
  // así que la carga inicial no necesita tocar estado sincrónicamente.
  const [reloadKey, setReloadKey] = useState(0)
  const reload = () => { setLoading(true); setReloadKey(k => k + 1) }

  useEffect(() => {
    let cancelled = false
    gateway.listEventTypes(token)
      .then(data => { if (!cancelled) setEventTypes(data) })
      .catch((err: Error) => { if (!cancelled) setError(err.message) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [token, reloadKey])

  const toggleExpand = (fqn: string) => {
    if (expandedFqn === fqn) { setExpandedFqn(null); return }
    setExpandedFqn(fqn)
    if (schemas[fqn]) return

    setSchemaLoading(fqn)
    gateway.getEventTypeSchema(token, fqn)
      .then(schema => setSchemas(prev => ({ ...prev, [fqn]: schema })))
      .catch((err: Error) => setError(err.message))
      // Si el usuario ya expandió otro, no le apagamos su indicador de carga.
      .finally(() => setSchemaLoading(prev => (prev === fqn ? null : prev)))
  }

  const handleArchive = (fqn: string) => {
    gateway.archiveEventType(token, fqn)
      .then(() => {
        setConfirmArchive(null)
        reload()
      })
      .catch((err: Error) => setError(err.message))
  }

  return (
    <section className="et-list card">
      <div className="et-list-header card-header">
        <h2 className="et-list-title card-title">
          Event Types
          {!loading && <span className="et-count">{eventTypes.length}</span>}
        </h2>
      </div>

      {error && <div className="et-error-wrap"><ErrorBanner message={error} onDismiss={() => setError('')} /></div>}

      {loading ? (
        <p className="et-empty">Cargando…</p>
      ) : eventTypes.length === 0 ? (
        <p className="et-empty">No hay event types registrados.</p>
      ) : (
        <ul className="et-items">
          {eventTypes.map(eventType => {
            const { fqn, schemaId, status } = eventType
            const isExpanded = expandedFqn === fqn
            const isLoadingSchema = schemaLoading === fqn
            const isSelected = selectedFqn === fqn
            const isArchived = status === 'archived'

            return (
              <li
                key={fqn}
                className={
                  'et-item' +
                  (isExpanded ? ' et-item--expanded' : '') +
                  (isSelected ? ' et-item--selected' : '') +
                  (isArchived ? ' et-item--archived' : '')
                }
              >
                <div className="et-item-row">
                  <button
                    className="et-expand-btn"
                    onClick={() => toggleExpand(fqn)}
                    aria-expanded={isExpanded}
                    aria-label={isExpanded ? 'Colapsar' : 'Expandir'}
                  >
                    <span className={`et-chevron${isExpanded ? ' et-chevron--open' : ''}`}>›</span>
                  </button>

                  <code
                    className="et-fqn"
                    onClick={() => (onSelect ? onSelect(eventType) : toggleExpand(fqn))}
                    aria-current={onSelect && isSelected ? 'true' : undefined}
                  >
                    {fqn}
                  </code>

                  {isArchived && (
                    <span className="et-badge" title="No admite nuevos eventos; su schema e historial siguen disponibles">
                      archivado
                    </span>
                  )}

                  {schemaId !== null && <span className="et-schema-id">#{schemaId}</span>}

                  {archivable && !isArchived && (confirmArchive === fqn ? (
                    <span className="et-confirm">
                      ¿Archivar?
                      <button className="et-confirm-yes" onClick={() => handleArchive(fqn)}>Sí</button>
                      <button className="et-confirm-no" onClick={() => setConfirmArchive(null)}>No</button>
                    </span>
                  ) : (
                    <button
                      className="et-archive-btn"
                      onClick={() => setConfirmArchive(fqn)}
                      title="Cierra el event type a nuevos eventos, sin borrar el schema ni el historial"
                    >
                      Archivar
                    </button>
                  ))}
                </div>

                {isExpanded && (
                  <div className="et-accordion">
                    {isLoadingSchema ? (
                      <p className="et-detail-loading">Cargando schema…</p>
                    ) : schemas[fqn] ? (
                      <SchemaDetail schema={schemas[fqn]} />
                    ) : null}
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
