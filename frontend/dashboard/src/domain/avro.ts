/**
 * Aplanado de un esquema Avro a filas para el visor.
 *
 * No reimplementa Avro: sólo lo suficiente para listar los campos con su tipo, su valor por
 * defecto y su documentación. El JSON crudo queda disponible al lado, que es lo que hay que
 * mirar cuando esta vista no alcanza.
 */

type AvroSchema = Record<string, unknown>

export type SchemaField = {
  /** Ruta con puntos: `direccion.calle`. Los anidados se muestran indentados. */
  path:     string
  name:     string
  type:     string
  depth:    number
  nullable: boolean
  doc:      string | null
  hasDefault: boolean
}

const isRecord = (v: unknown): v is AvroSchema =>
  v !== null && typeof v === 'object' && !Array.isArray(v)

/**
 * Nombre legible de un tipo Avro.
 *
 * Una unión con `null` es la forma que tiene Avro de decir «opcional», así que se muestra como
 * el tipo que no es nulo, y lo opcional se marca aparte: `["null","string"]` se lee mejor como
 * `string` opcional que como una unión de dos.
 */
export function typeLabel(type: unknown): string {
  if (typeof type === 'string') return type
  if (Array.isArray(type)) {
    const withoutNull = type.filter(t => t !== 'null')
    if (withoutNull.length === 1) return typeLabel(withoutNull[0])
    return withoutNull.map(typeLabel).join(' | ')
  }
  if (isRecord(type)) {
    const kind = type.type
    if (kind === 'array')  return `array<${typeLabel(type.items)}>`
    if (kind === 'map')    return `map<${typeLabel(type.values)}>`
    if (kind === 'enum')   return `enum ${String(type.name ?? '')}`.trim()
    if (kind === 'record') return `record ${String(type.name ?? '')}`.trim()
    if (type.logicalType)  return `${String(kind)} (${String(type.logicalType)})`
    return String(kind ?? 'desconocido')
  }
  return 'desconocido'
}

const isNullable = (type: unknown): boolean =>
  Array.isArray(type) && type.includes('null')

/** El record que hay detrás de un tipo, si lo hay: mira dentro de uniones, arrays y maps. */
function recordOf(type: unknown): AvroSchema | null {
  if (Array.isArray(type)) {
    for (const option of type) {
      const found = recordOf(option)
      if (found) return found
    }
    return null
  }
  if (!isRecord(type)) return null
  if (type.type === 'record' && Array.isArray(type.fields)) return type
  if (type.type === 'array') return recordOf(type.items)
  if (type.type === 'map')   return recordOf(type.values)
  return null
}

/**
 * @param maxDepth Corte de seguridad. Un esquema recursivo —un record que se referencia a sí
 *                 mismo— haría bucle infinito, y el gateway no impide registrarlo.
 */
export function flattenSchema(schema: unknown, maxDepth = 6): SchemaField[] {
  const rows: SchemaField[] = []

  const walk = (node: unknown, prefix: string, depth: number) => {
    const record = recordOf(node)
    if (!record || depth > maxDepth) return
    const fields = record.fields as unknown[]
    for (const field of fields) {
      if (!isRecord(field)) continue
      const name = String(field.name ?? '')
      const path = prefix ? `${prefix}.${name}` : name
      rows.push({
        path,
        name,
        type:       typeLabel(field.type),
        depth,
        nullable:   isNullable(field.type),
        doc:        typeof field.doc === 'string' ? field.doc : null,
        hasDefault: 'default' in field,
      })
      walk(field.type, path, depth + 1)
    }
  }

  walk(schema, '', 0)
  return rows
}
