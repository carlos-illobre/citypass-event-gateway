import { useMemo, useState, type ReactNode } from 'react'
import { JsonView } from '@/components/ui/JsonView'
import { DATA_FIELD, METADATA_FIELD, toAvroFields, type FieldDef } from '@/domain/avro'
import { sampleEvent } from '@/domain/sample'
import type { EventTypeSchema } from '@/api/gateway'
import './PreviewPanel.css'

type Props = {
  namespace: string
  name:      string
  fields:    FieldDef[]
  /** Record EventMetadata traído del gateway; null mientras carga. */
  metadata:  EventTypeSchema | null
}

const PLACEHOLDER = '… cargando desde el gateway'

/** En el evento el sobre cuelga de `metadata`. */
const collapseMetadata = (path: string) =>
  path === METADATA_FIELD || path.startsWith(`${METADATA_FIELD}.`)

/** En el schema la metadata es el segundo campo, después del record `data`. */
const collapseMetadataField = (path: string) =>
  path === 'fields.1.type' || path.startsWith('fields.1.type.')

function JsonCard({ title, note, value, collapsedByDefault }: {
  title:  string
  note:   ReactNode
  value:  unknown
  collapsedByDefault: (path: string) => boolean
}) {
  const [copied, setCopied] = useState(false)

  const copy = () => {
    navigator.clipboard.writeText(JSON.stringify(value, null, 2))
      .then(() => {
        setCopied(true)
        setTimeout(() => setCopied(false), 1500)
      })
      .catch(() => { /* clipboard bloqueado: sin acción */ })
  }

  return (
    <section className="pp card">
      <div className="pp-header card-header">
        <h3 className="card-title">{title}</h3>
        <button type="button" className="pp-copy" onClick={copy}>
          {copied ? '✓ copiado' : 'Copiar'}
        </button>
      </div>
      <p className="pp-note">{note}</p>
      <div className="pp-body">
        <JsonView value={value} collapsedByDefault={collapsedByDefault} />
      </div>
    </section>
  )
}

/**
 * Columna derecha: el schema que se va a registrar y el evento que va a producir.
 *
 * Los dos se muestran a la vez —y no en solapas— para poder ver el efecto de cada
 * campo nuevo sobre ambos sin cambiar de vista.
 */
export function PreviewPanel({ namespace, name, fields, metadata }: Props) {
  const typeName  = name.trim()
  const eventType = typeName ? `${namespace}.${typeName}` : namespace

  const dataFields = useMemo(() => toAvroFields(fields), [fields])

  const schema = useMemo(() => ({
    type:      'record',
    name:      typeName,
    namespace,
    // Mismo orden que arma el gateway: primero el negocio, después el sobre.
    fields: [
      {
        name: DATA_FIELD,
        type: { type: 'record', name: typeName, namespace: `${namespace}.data`, fields: dataFields },
      },
      { name: METADATA_FIELD, type: metadata ?? PLACEHOLDER },
    ],
  }), [typeName, namespace, dataFields, metadata])

  const event = useMemo(
    () => sampleEvent(dataFields, metadata?.fields ?? [], eventType),
    [dataFields, metadata, eventType]
  )

  return (
    <div className="pp-stack">
      <JsonCard
        title="Schema Avro"
        note={<>Tus campos van en <code>data</code>; <code>metadata</code> la agrega el gateway.
              Al estar separados podés usar cualquier nombre de campo sin conflicto.</>}
        value={schema}
        collapsedByDefault={collapseMetadataField}
      />
      <JsonCard
        title="Evento de ejemplo"
        note={<>Así llega el evento al consumidor. Los valores son de muestra,
              derivados de los tipos que definiste.</>}
        value={event}
        collapsedByDefault={collapseMetadata}
      />
    </div>
  )
}
