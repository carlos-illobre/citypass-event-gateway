import { useContext, useEffect, useState } from 'react'
import { gateway } from '@/api/gateway'
import { AuthContext } from '@/contexts/auth-context'
import { flattenSchema, type SchemaField } from '@/domain/avro'
import { ErrorBanner } from '@/components/ui/ErrorBanner'
import { JsonView } from '@/components/ui/JsonView'
import { Badge } from '@/components/ui/Badge'
import './SchemaViewer.css'

type Props = {
  fqn: string
}

function FieldTable({ fields, caption }: { fields: SchemaField[]; caption: string }) {
  if (fields.length === 0) return <p className="muted">Sin campos.</p>
  return (
    <table className="schema-table">
      <caption className="schema-table__caption">{caption}</caption>
      <thead>
        <tr><th>Campo</th><th>Tipo</th><th>Notas</th></tr>
      </thead>
      <tbody>
        {fields.map(f => (
          <tr key={f.path}>
            <td>
              <span className="mono" style={{ paddingLeft: `${f.depth * 1}rem` }}>{f.name}</span>
            </td>
            <td className="mono muted">{f.type}</td>
            <td className="schema-table__notes">
              {f.nullable && <Badge tone="neutral">opcional</Badge>}
              {f.hasDefault && <Badge tone="neutral">con default</Badge>}
              {f.doc && <span className="schema-table__doc">{f.doc}</span>}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/**
 * El esquema de un tipo, junto con el del sobre.
 *
 * Los dos, y no sólo el primero: todo evento del bus viaja como `{ data, metadata }`, así que
 * quien está por escribir un consumidor necesita ver las dos mitades del contrato. Mostrar sólo
 * el `data` deja la mitad del trabajo para descubrir a mano.
 *
 * La consulta se dispara al expandir la fila y no se sondea: un esquema registrado no cambia —
 * el Schema Registry rechaza las versiones incompatibles— así que repreguntarlo sería gastar
 * cuota a cambio de nada.
 */
export function SchemaViewer({ fqn }: Props) {
  const { token } = useContext(AuthContext)
  const [schema, setSchema]     = useState<Record<string, unknown> | null>(null)
  const [metadata, setMetadata] = useState<Record<string, unknown> | null>(null)
  const [error, setError]       = useState('')
  const [loading, setLoading]   = useState(true)

  // `loading` arranca en true y no se vuelve a poner acá: cada fila desplegada monta su propio
  // `SchemaViewer`, así que `fqn` no cambia durante la vida del componente y no hay nada que
  // reiniciar. Ponerlo dentro del efecto sería un setState sincrónico, que dispara un render en
  // cascada — el React Compiler lo marca como error.
  useEffect(() => {
    if (!token) return
    const controller = new AbortController()
    Promise.all([
      gateway.getEventTypeSchema(token, fqn, controller.signal),
      gateway.getMetadataSchema(token, controller.signal),
    ])
      .then(([tipo, sobre]) => { setSchema(tipo); setMetadata(sobre); setError('') })
      .catch((err: Error) => { if (!controller.signal.aborted) setError(err.message) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [token, fqn])

  const copyFqn = () => { void navigator.clipboard?.writeText(fqn) }

  if (loading) return <p className="muted">Cargando el esquema…</p>
  if (error) return <ErrorBanner message={error} />

  return (
    <div className="schema-viewer">
      <div className="schema-viewer__head">
        <span className="mono">{fqn}</span>
        <button className="btn-ghost" type="button" onClick={copyFqn}>Copiar FQN</button>
      </div>

      <div className="schema-viewer__grid">
        <div>
          <FieldTable fields={flattenSchema(schema)} caption="Campos del evento (data)" />
          <FieldTable fields={flattenSchema(metadata)} caption="Campos del sobre (metadata)" />
        </div>
        <div>
          <p className="schema-viewer__label">Esquema Avro sin procesar</p>
          <JsonView value={schema} collapsedByDefault={path => path.split('.').length > 2} />
        </div>
      </div>
    </div>
  )
}
