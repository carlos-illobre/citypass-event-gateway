// Lógica de dominio Avro. Sin dependencias: no importa API, config ni React.

// ── Envelope metadata / data ──────────────────────────────────────────────────
//
// El gateway envuelve todo schema en un record de dos campos: `metadata`, que
// calcula él, y `data`, con los campos del productor. Como viven en records
// separados no hay nombres reservados — un campo de negocio puede llamarse
// `source` o `eventId` sin colisionar con la metadata.

export const METADATA_FIELD = 'metadata'
export const DATA_FIELD     = 'data'

export type RawField = { name: string; type: unknown }

/** El record anidado bajo `fieldName`, o null si el schema no tiene ese formato. */
function nestedRecord(schemaFields: unknown, fieldName: string): { name: string; fields: RawField[] } | null {
  if (!Array.isArray(schemaFields)) return null

  const field = schemaFields.find(f => (f as { name?: unknown } | null)?.name === fieldName)
  const type  = (field as { type?: unknown } | undefined)?.type
  if (type === null || typeof type !== 'object') return null

  const o = type as Record<string, unknown>
  if (o.type !== 'record' || !Array.isArray(o.fields)) return null

  return { name: String(o.name ?? ''), fields: o.fields as RawField[] }
}

/** Campos de negocio de un schema con envelope. */
export const dataRecordOf = (schemaFields: unknown) => nestedRecord(schemaFields, DATA_FIELD)

/** Campos que inyecta el gateway. */
export const metadataRecordOf = (schemaFields: unknown) => nestedRecord(schemaFields, METADATA_FIELD)

// ── Modelo ────────────────────────────────────────────────────────────────────

export type Primitive = 'string' | 'int' | 'long' | 'float' | 'double' | 'boolean' | 'bytes'

export type LogicalType =
  | 'date' | 'time-millis' | 'timestamp-millis' | 'timestamp-micros' | 'uuid' | 'decimal'

export type TypeDef =
  | { kind: 'primitive'; primitive: Primitive }
  | { kind: 'logical';   logical: LogicalType; precision: number; scale: number }
  | { kind: 'array';     items: TypeDef }
  | { kind: 'map';       values: TypeDef }
  | { kind: 'record';    recordName: string; fields: FieldDef[] }
  | { kind: 'enum';      enumName: string; symbols: string[] }
  | { kind: 'ref';       refName: string }

export type Kind = TypeDef['kind']

export type FieldDef = {
  id:        string
  name:      string
  nullable:  boolean
  collapsed: boolean
  typeDef:   TypeDef
}

export const PRIMITIVES: Primitive[] =
  ['string', 'int', 'long', 'float', 'double', 'boolean', 'bytes']

/** Tipo Avro subyacente de cada logical type. */
export const LOGICAL_BASE: Record<LogicalType, Primitive> = {
  'date':             'int',
  'time-millis':      'int',
  'timestamp-millis': 'long',
  'timestamp-micros': 'long',
  'uuid':             'string',
  'decimal':          'bytes',
}

export const LOGICAL_TYPES = Object.keys(LOGICAL_BASE) as LogicalType[]

export const KIND_LABELS: Record<Kind, string> = {
  primitive: 'Primitivo',
  logical:   'Lógico',
  array:     'Array',
  map:       'Map',
  record:    'Record',
  enum:      'Enum',
  ref:       'Referencia',
}

export type NamedTypeDef = Extract<TypeDef, { kind: 'record' } | { kind: 'enum' }>

/**
 * Desciende por los wrappers array/map hasta el record o enum interno.
 * Permite que `array de record` abra el mismo panel de edición que `record`.
 */
export function namedInside(td: TypeDef): NamedTypeDef | null {
  switch (td.kind) {
    case 'record':
    case 'enum':  return td
    case 'array': return namedInside(td.items)
    case 'map':   return namedInside(td.values)
    default:      return null
  }
}

/** Reemplaza el record/enum interno preservando los wrappers array/map. */
export function replaceNamedInside(td: TypeDef, next: NamedTypeDef): TypeDef {
  switch (td.kind) {
    case 'record':
    case 'enum':  return next
    case 'array': return { kind: 'array', items: replaceNamedInside(td.items, next) }
    case 'map':   return { kind: 'map', values: replaceNamedInside(td.values, next) }
    default:      return td
  }
}

// ── Constructores ─────────────────────────────────────────────────────────────

export const makeField = (): FieldDef => ({
  id:        crypto.randomUUID(),
  name:      '',
  nullable:  false,
  collapsed: false,
  typeDef:   { kind: 'primitive', primitive: 'string' },
})

export function defaultTypeDef(kind: Kind): TypeDef {
  switch (kind) {
    case 'primitive': return { kind: 'primitive', primitive: 'string' }
    case 'logical':   return { kind: 'logical', logical: 'timestamp-millis', precision: 10, scale: 2 }
    case 'array':     return { kind: 'array', items: { kind: 'primitive', primitive: 'string' } }
    case 'map':       return { kind: 'map', values: { kind: 'primitive', primitive: 'string' } }
    case 'record':    return { kind: 'record', recordName: '', fields: [makeField()] }
    case 'enum':      return { kind: 'enum', enumName: '', symbols: [''] }
    case 'ref':       return { kind: 'ref', refName: '' }
  }
}

/** Clona un campo (y todo su subárbol) con ids nuevos. */
export function cloneField(field: FieldDef): FieldDef {
  return { ...field, id: crypto.randomUUID(), typeDef: cloneType(field.typeDef) }
}

function cloneType(t: TypeDef): TypeDef {
  switch (t.kind) {
    case 'record': return { ...t, fields: t.fields.map(cloneField) }
    case 'array':  return { ...t, items: cloneType(t.items) }
    case 'map':    return { ...t, values: cloneType(t.values) }
    case 'enum':   return { ...t, symbols: [...t.symbols] }
    default:       return { ...t }
  }
}

// ── FieldDef → Avro ───────────────────────────────────────────────────────────

export function toAvroFields(fields: FieldDef[]): { name: string; type: unknown }[] {
  return fields
    .filter(f => f.name.trim())
    .map(f => ({ name: f.name.trim(), type: toAvroType(f.typeDef, f.nullable) }))
}

function toAvroType(td: TypeDef, nullable: boolean): unknown {
  let base: unknown
  switch (td.kind) {
    case 'primitive':
      base = td.primitive
      break
    case 'logical':
      base = td.logical === 'decimal'
        ? { type: 'bytes', logicalType: 'decimal', precision: td.precision, scale: td.scale }
        : { type: LOGICAL_BASE[td.logical], logicalType: td.logical }
      break
    case 'array':
      base = { type: 'array', items: toAvroType(td.items, false) }
      break
    case 'map':
      base = { type: 'map', values: toAvroType(td.values, false) }
      break
    case 'record':
      base = { type: 'record', name: td.recordName.trim(), fields: toAvroFields(td.fields) }
      break
    case 'enum':
      base = {
        type:    'enum',
        name:    td.enumName.trim(),
        symbols: td.symbols.map(s => s.trim()).filter(Boolean),
      }
      break
    case 'ref':
      base = td.refName.trim()
      break
  }
  return nullable ? ['null', base] : base
}

// ── Avro → FieldDef ───────────────────────────────────────────────────────────

/** Inversa de toAvroFields: reconstruye el árbol editable desde un schema Avro. */
export function fromAvroFields(fields: unknown): FieldDef[] {
  if (!Array.isArray(fields)) return []
  return fields.map(raw => {
    const f = (raw ?? {}) as { name?: unknown; type?: unknown }
    const { typeDef, nullable } = fromAvroType(f.type)
    return {
      id:        crypto.randomUUID(),
      name:      typeof f.name === 'string' ? f.name : '',
      nullable,
      collapsed: false,
      typeDef,
    }
  })
}

function fromAvroType(t: unknown): { typeDef: TypeDef; nullable: boolean } {
  if (Array.isArray(t)) {
    const nullable = t.includes('null')
    const rest = t.filter(x => x !== 'null')
    if (rest.length === 1) return { typeDef: fromAvroType(rest[0]).typeDef, nullable }
    return { typeDef: defaultTypeDef('primitive'), nullable }
  }

  if (typeof t === 'string') {
    return PRIMITIVES.includes(t as Primitive)
      ? { typeDef: { kind: 'primitive', primitive: t as Primitive }, nullable: false }
      : { typeDef: { kind: 'ref', refName: t }, nullable: false }
  }

  if (t !== null && typeof t === 'object') {
    const o = t as Record<string, unknown>

    if (typeof o.logicalType === 'string' && o.logicalType in LOGICAL_BASE) {
      return {
        typeDef: {
          kind:      'logical',
          logical:   o.logicalType as LogicalType,
          precision: typeof o.precision === 'number' ? o.precision : 10,
          scale:     typeof o.scale === 'number' ? o.scale : 2,
        },
        nullable: false,
      }
    }
    if (o.type === 'array')
      return { typeDef: { kind: 'array', items: fromAvroType(o.items).typeDef }, nullable: false }
    if (o.type === 'map')
      return { typeDef: { kind: 'map', values: fromAvroType(o.values).typeDef }, nullable: false }
    if (o.type === 'record')
      return {
        typeDef: { kind: 'record', recordName: String(o.name ?? ''), fields: fromAvroFields(o.fields) },
        nullable: false,
      }
    if (o.type === 'enum')
      return {
        typeDef: {
          kind:    'enum',
          enumName: String(o.name ?? ''),
          symbols:  Array.isArray(o.symbols) ? o.symbols.map(String) : [''],
        },
        nullable: false,
      }
    if (typeof o.type === 'string') return fromAvroType(o.type)
  }

  return { typeDef: defaultTypeDef('primitive'), nullable: false }
}

// ── Referencias a tipos nombrados ─────────────────────────────────────────────

/**
 * Para cada campo, los tipos nombrados definidos *antes* que él en orden DFS.
 * Avro exige que un tipo esté definido antes de ser referenciado.
 */
export function collectRefScopes(fields: FieldDef[]): Map<string, string[]> {
  const scope = new Map<string, string[]>()
  const seen: string[] = []

  const walkType = (td: TypeDef) => {
    switch (td.kind) {
      case 'record':
        if (td.recordName.trim()) seen.push(td.recordName.trim())
        walkFields(td.fields)
        break
      case 'enum':
        if (td.enumName.trim()) seen.push(td.enumName.trim())
        break
      case 'array': walkType(td.items);  break
      case 'map':   walkType(td.values); break
    }
  }

  const walkFields = (fs: FieldDef[]) => {
    for (const f of fs) {
      scope.set(f.id, [...seen])
      walkType(f.typeDef)
    }
  }

  walkFields(fields)
  return scope
}

// ── Validación ────────────────────────────────────────────────────────────────

export type Issue = { fieldId?: string; path: string; message: string }

const NAME_RE = /^[A-Za-z_][A-Za-z0-9_]*$/
const NAME_HINT = 'debe empezar con letra o _ y contener solo letras, dígitos y _'

export function validate(typeName: string, fields: FieldDef[]): Issue[] {
  const issues: Issue[] = []
  const namedTypes = new Map<string, number>()
  const refs: { fieldId: string; path: string; name: string }[] = []

  const declareNamed = (n: string) => namedTypes.set(n, (namedTypes.get(n) ?? 0) + 1)

  const root = typeName.trim()
  if (!root) {
    issues.push({ path: 'nombre', message: 'El nombre del event type es obligatorio' })
  } else if (!NAME_RE.test(root)) {
    issues.push({ path: 'nombre', message: `"${root}" no es válido: ${NAME_HINT}` })
  } else {
    declareNamed(root)
  }

  const walkType = (td: TypeDef, path: string, fieldId: string) => {
    switch (td.kind) {
      case 'record': {
        const n = td.recordName.trim()
        if (!n) issues.push({ fieldId, path, message: `${path}: el record necesita un nombre` })
        else if (!NAME_RE.test(n)) issues.push({ fieldId, path, message: `${path}: "${n}" no es válido: ${NAME_HINT}` })
        else declareNamed(n)
        if (td.fields.length === 0) issues.push({ fieldId, path, message: `${path}: el record no tiene campos` })
        walkFields(td.fields, path)
        break
      }
      case 'enum': {
        const n = td.enumName.trim()
        if (!n) issues.push({ fieldId, path, message: `${path}: el enum necesita un nombre` })
        else if (!NAME_RE.test(n)) issues.push({ fieldId, path, message: `${path}: "${n}" no es válido: ${NAME_HINT}` })
        else declareNamed(n)

        const syms = td.symbols.map(s => s.trim()).filter(Boolean)
        if (syms.length === 0)
          issues.push({ fieldId, path, message: `${path}: el enum necesita al menos un símbolo` })
        for (const s of syms)
          if (!NAME_RE.test(s))
            issues.push({ fieldId, path, message: `${path}: el símbolo "${s}" no es válido: ${NAME_HINT}` })
        if (new Set(syms).size !== syms.length)
          issues.push({ fieldId, path, message: `${path}: hay símbolos repetidos en el enum` })
        break
      }
      case 'logical':
        if (td.logical === 'decimal') {
          if (!Number.isInteger(td.precision) || td.precision < 1)
            issues.push({ fieldId, path, message: `${path}: precision debe ser un entero ≥ 1` })
          if (!Number.isInteger(td.scale) || td.scale < 0)
            issues.push({ fieldId, path, message: `${path}: scale debe ser un entero ≥ 0` })
          else if (td.scale > td.precision)
            issues.push({ fieldId, path, message: `${path}: scale no puede superar a precision` })
        }
        break
      case 'ref': {
        const n = td.refName.trim()
        if (!n) issues.push({ fieldId, path, message: `${path}: elegí el tipo a referenciar` })
        else refs.push({ fieldId, path, name: n })
        break
      }
      case 'array': walkType(td.items, path, fieldId);  break
      case 'map':   walkType(td.values, path, fieldId); break
    }
  }

  const walkFields = (fs: FieldDef[], path: string) => {
    const seen = new Set<string>()
    for (const f of fs) {
      const n = f.name.trim()
      const p = path ? `${path}.${n || '?'}` : (n || '?')

      if (!n) {
        issues.push({ fieldId: f.id, path: p, message: `${p}: el campo no tiene nombre` })
      } else if (!NAME_RE.test(n)) {
        issues.push({ fieldId: f.id, path: p, message: `${p}: "${n}" no es válido: ${NAME_HINT}` })
      } else if (seen.has(n)) {
        issues.push({ fieldId: f.id, path: p, message: `${p}: campo duplicado en el mismo nivel` })
      }
      if (n) seen.add(n)

      walkType(f.typeDef, p, f.id)
    }
  }

  walkFields(fields, '')

  for (const [name, count] of namedTypes)
    if (count > 1)
      issues.push({ path: name, message: `El tipo "${name}" está definido ${count} veces; los nombres deben ser únicos` })

  for (const ref of refs)
    if (!namedTypes.has(ref.name))
      issues.push({ fieldId: ref.fieldId, path: ref.path, message: `${ref.path}: no existe el tipo "${ref.name}"` })

  return issues
}
