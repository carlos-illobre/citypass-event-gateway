import { useContext, useEffect, useState } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { ApiError } from '@/api/client'
import { gateway, type EventTypeQuota, type EventTypeSchema, type EventTypeSummary } from '@/api/gateway'
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

/**
 * Las versiones mayores de un event type.
 *
 * Sólo se muestra si hay más de una: un event type que nunca rompió su contrato tiene
 * una sola, y enumerarla no diría nada.
 */
function VersionsTable({ eventType, onRetire }: {
  eventType: EventTypeSummary
  onRetire?: (version: number) => void
}) {
  return (
    <table className="et-fields-table">
      <caption className="et-fields-caption">
        versiones — cada una con su tópico; las viejas siguen sirviendo su historial
      </caption>
      <thead>
        <tr>
          <th>Versión</th>
          <th>Tópico</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {eventType.versions.map(v => {
          const vigente = v.version === eventType.version
          return (
            <tr key={v.version} className={vigente ? '' : 'et-field-base'}>
              <td>
                <code>v{v.version}</code>
                {vigente && <span className="et-version-tag">vigente</span>}
              </td>
              <td><code>{v.topic}</code></td>
              <td>
                {/* La vigente no se puede retirar: dejaría al event type sin dónde publicar. */}
                {!vigente && onRetire && (
                  <button className="et-retire-btn" onClick={() => onRetire(v.version)}>
                    Retirar
                  </button>
                )}
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

function SchemaDetail({ schema, eventType, onRetire }: {
  schema:    EventTypeSchema
  eventType: EventTypeSummary
  onRetire?: (version: number) => void
}) {
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
        <span className="et-detail-meta-item">
          <span className="et-detail-label">tópico</span>
          <code>{eventType.topic}</code>
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

      {eventType.versions.length > 1 && (
        <VersionsTable eventType={eventType} onRetire={onRetire} />
      )}
    </div>
  )
}

/** Los equipos que un borrado dejaría sin eventos, tal como los nombra el 409. */
type Subscriber = { owner: string; topic: string }

function subscribersOf(err: unknown): Subscriber[] {
  if (!(err instanceof ApiError)) return []
  const subs = err.problem.subscribers
  return Array.isArray(subs) ? subs as Subscriber[] : []
}

/**
 * El aviso de que hay otros equipos consumiendo.
 *
 * Se los nombra en vez de decir sólo «no se puede»: sin saber quiénes son, quien
 * recibe el rechazo no tiene con quién coordinar la baja.
 */
function BlockedBySubscribers({ subscribers, onDismiss }: {
  subscribers: Subscriber[]
  onDismiss:   () => void
}) {
  return (
    <div className="et-blocked">
      <div className="et-blocked-head">
        <strong>No se puede borrar todavía</strong>
        <button className="et-blocked-close" onClick={onDismiss} aria-label="Cerrar">✕</button>
      </div>
      <p className="et-blocked-text">
        Estos equipos siguen recibiendo estos eventos. Coordiná la baja con ellos antes
        de borrar:
      </p>
      <ul className="et-blocked-list">
        {subscribers.map((s, i) => (
          <li key={i}><code>{s.owner}</code> <span className="et-muted">en</span> <code>{s.topic}</code></li>
        ))}
      </ul>
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
  /** Recibe el resumen completo, no sólo el FQN: quien elige suele necesitar el tópico. */
  onSelect?:    (eventType: EventTypeSummary) => void
  /** Editar y borrar se ocultan donde la lista se usa para elegir, no para administrar. */
  manageable?:  boolean
  /** Carga el event type en el formulario para cambiarle el schema. */
  onEdit?:      (eventType: EventTypeSummary) => void
  /** Avisa de un borrado, para que quien esté editando ese event type se entere. */
  onDeleted?:   (fqn: string) => void
}

export function EventTypeList({
  selectedFqn = null, onSelect, manageable = true, onEdit, onDeleted,
}: Props = {}) {
  const { token } = useContext(AuthContext)
  const [eventTypes, setEventTypes]       = useState<EventTypeSummary[]>([])
  const [loading, setLoading]             = useState(true)
  const [error, setError]                 = useState('')
  const [blockedBy, setBlockedBy]         = useState<Subscriber[]>([])
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null)
  const [expandedFqn, setExpandedFqn]     = useState<string | null>(null)
  const [schemas, setSchemas]             = useState<Record<string, EventTypeSchema>>({})
  const [schemaLoading, setSchemaLoading] = useState<string | null>(null)
  const [cupo, setCupo]                   = useState<EventTypeQuota | null>(null)

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

    // El cupo se pide aparte y su fallo se ignora: no poder mostrar el contador no es
    // motivo para dejar sin lista a quien vino a ver sus event types.
    gateway.getQuota(token)
      .then(q => { if (!cancelled) setCupo(q) })
      .catch(() => {})

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

  /** Limpia lo que quedó de un intento anterior antes de mostrar el resultado nuevo. */
  const clearFeedback = () => { setError(''); setBlockedBy([]) }

  const reportFailure = (err: Error) => {
    const subs = subscribersOf(err)
    // El 409 por suscriptores ajenos no es un error a secas: es una lista de gente con
    // la que hay que hablar, así que se muestra aparte del banner de error.
    if (subs.length > 0) setBlockedBy(subs)
    else setError(err.message)
  }

  const handleDelete = (fqn: string) => {
    clearFeedback()
    gateway.deleteEventType(token, fqn)
      .then(() => {
        setConfirmDelete(null)
        onDeleted?.(fqn)
        reload()
      })
      .catch((err: Error) => { setConfirmDelete(null); reportFailure(err) })
  }

  const handleRetire = (fqn: string, version: number) => {
    clearFeedback()
    gateway.deleteEventTypeVersion(token, fqn, version)
      // El schema cacheado no cambia, pero sí la lista de versiones del resumen.
      .then(reload)
      .catch((err: Error) => reportFailure(err))
  }

  return (
    <section className="et-list card">
      <div className="et-list-header card-header">
        <h2 className="et-list-title card-title">
          Event Types
          {!loading && (
            cupo
              // Contra el cupo y no a secas: el número solo no dice si queda lugar, que
              // es lo que hace falta saber antes de intentar crear uno más.
              ? <span
                  className={`et-count${cupo.remaining === 0 || cupo.totalRemaining === 0 ? ' et-count--lleno' : ''}`}
                  title={`${cupo.used} de ${cupo.limit} tópicos en ${cupo.namespace} · ${cupo.totalUsed} de ${cupo.totalLimit} en todo el bus. Cada versión mayor de un event type cuenta como un tópico.`}
                >
                  {cupo.used} / {cupo.limit}
                </span>
              : <span className="et-count">{eventTypes.length}</span>
          )}
        </h2>
      </div>

      {error && <div className="et-error-wrap"><ErrorBanner message={error} onDismiss={() => setError('')} /></div>}
      {blockedBy.length > 0 && (
        <div className="et-error-wrap">
          <BlockedBySubscribers subscribers={blockedBy} onDismiss={() => setBlockedBy([])} />
        </div>
      )}

      {/* Dos techos distintos y dos mensajes distintos: uno se resuelve borrando algo
          propio y el otro puede estar agotado por event types de otros equipos. */}
      {cupo && cupo.remaining === 0 && (
        <p className="et-cupo-lleno">
          Llegaste al máximo de {cupo.limit} tópicos. Cada versión mayor cuenta como uno,
          así que también liberan lugar las versiones viejas que ya nadie lee.
        </p>
      )}
      {cupo && cupo.remaining > 0 && cupo.totalRemaining === 0 && (
        <p className="et-cupo-lleno">
          El bus llegó a su máximo de {cupo.totalLimit} tópicos entre todos los
          equipos. Te queda lugar propio, pero nadie puede crear uno nuevo hasta que se
          borre alguno de los existentes.
        </p>
      )}

      {loading ? (
        <p className="et-empty">Cargando…</p>
      ) : eventTypes.length === 0 ? (
        <p className="et-empty">No hay event types registrados.</p>
      ) : (
        <ul className="et-items">
          {eventTypes.map(eventType => {
            const { fqn, schemaId, version } = eventType
            const isExpanded = expandedFqn === fqn
            const isLoadingSchema = schemaLoading === fqn
            const isSelected = selectedFqn === fqn

            return (
              <li
                key={fqn}
                className={
                  'et-item' +
                  (isExpanded ? ' et-item--expanded' : '') +
                  (isSelected ? ' et-item--selected' : '')
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

                  {/* La v1 no se anuncia: un sufijo significa que hubo una ruptura. */}
                  {version > 1 && (
                    <span
                      className="et-badge"
                      title={`Su contrato se rompió ${version - 1} vez(ces). Los eventos nuevos van a ${eventType.topic}`}
                    >
                      v{version}
                    </span>
                  )}

                  {schemaId !== null && <span className="et-schema-id">#{schemaId}</span>}

                  {manageable && (confirmDelete === fqn ? (
                    <span className="et-confirm">
                      ¿Borrar todo?
                      <button className="et-confirm-yes" onClick={() => handleDelete(fqn)}>Sí</button>
                      <button className="et-confirm-no" onClick={() => setConfirmDelete(null)}>No</button>
                    </span>
                  ) : (
                    <>
                      {onEdit && (
                        <button
                          className="et-edit-btn"
                          onClick={() => onEdit(eventType)}
                          title="Cargar su schema en el formulario para cambiarle los campos"
                        >
                          Editar
                        </button>
                      )}
                      <button
                        className="et-delete-btn"
                        onClick={() => { clearFeedback(); setConfirmDelete(fqn) }}
                        title="Borra todas sus versiones, sus tópicos y sus eventos. Es permanente"
                      >
                        Borrar
                      </button>
                    </>
                  ))}
                </div>

                {isExpanded && (
                  <div className="et-accordion">
                    {isLoadingSchema ? (
                      <p className="et-detail-loading">Cargando schema…</p>
                    ) : schemas[fqn] ? (
                      <SchemaDetail
                        schema={schemas[fqn]}
                        eventType={eventType}
                        onRetire={manageable ? v => handleRetire(fqn, v) : undefined}
                      />
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
