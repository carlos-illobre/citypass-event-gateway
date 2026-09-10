import { describe, it, expect } from 'vitest'
import {
  METADATA_FIELD, DATA_FIELD, dataRecordOf, metadataRecordOf, formatType,
  makeField, defaultTypeDef, cloneField, namedInside, replaceNamedInside,
  toAvroFields, fromAvroFields, collectRefScopes, validate,
  type FieldDef, type TypeDef,
} from './avro'

const prim = (kind: string): TypeDef => ({ kind: 'primitive', primitive: kind as never })

const field = (name: string, typeDef: TypeDef, nullable = false): FieldDef =>
  ({ id: name, name, nullable, collapsed: false, typeDef })

describe('envelope metadata / data', () => {
  const schemaFields = [
    { name: DATA_FIELD, type: { type: 'record', name: 'X', fields: [{ name: 'a', type: 'string' }] } },
    { name: METADATA_FIELD, type: { type: 'record', name: 'EventMetadata', fields: [{ name: 'eventId', type: 'string' }] } },
  ]

  it('extrae el record de data', () => {
    expect(dataRecordOf(schemaFields)).toEqual({ name: 'X', fields: [{ name: 'a', type: 'string' }] })
  })

  it('extrae el record de metadata', () => {
    expect(metadataRecordOf(schemaFields)?.name).toBe('EventMetadata')
  })

  it('devuelve null si el schema no tiene ese formato', () => {
    expect(dataRecordOf(undefined)).toBeNull()
    expect(dataRecordOf([])).toBeNull()
    expect(dataRecordOf([{ name: DATA_FIELD, type: 'string' }])).toBeNull()
    expect(dataRecordOf([{ name: DATA_FIELD, type: { type: 'record' } }])).toBeNull()
  })

  it('un record sin `name` propio cae a un nombre vacío en vez de romper', () => {
    const r = dataRecordOf([{ name: DATA_FIELD, type: { type: 'record', fields: [] } }])
    expect(r?.name).toBe('')
  })
})

describe('formatType', () => {
  it('un primitivo se muestra tal cual', () => {
    expect(formatType('string')).toBe('string')
  })

  it('una unión con null se marca con ? y descarta el null de la lista', () => {
    expect(formatType(['null', 'int'])).toBe('int?')
  })

  it('una unión de varias ramas no nulas se separa con |', () => {
    expect(formatType(['int', 'string'])).toBe('int | string')
    expect(formatType(['null', 'int', 'string'])).toBe('int | string?')
  })

  it('un array se marca con []', () => {
    expect(formatType({ type: 'array', items: 'string' })).toBe('string[]')
  })

  it('un array de arrays anida los corchetes', () => {
    expect(formatType({ type: 'array', items: { type: 'array', items: 'int' } })).toBe('int[][]')
  })

  it('un record muestra su nombre', () => {
    expect(formatType({ type: 'record', name: 'Coord', fields: [] })).toBe('Coord')
  })

  it('un record sin nombre no rompe: string vacío', () => {
    expect(formatType({ type: 'record', fields: [] })).toBe('')
  })

  it('un map se muestra como Map<valor>', () => {
    expect(formatType({ type: 'map', values: 'int' })).toBe('Map<int>')
  })

  it('cualquier otra cosa cae al JSON crudo', () => {
    expect(formatType({ type: 'bytes', logicalType: 'decimal', precision: 9, scale: 2 }))
      .toBe(JSON.stringify({ type: 'bytes', logicalType: 'decimal', precision: 9, scale: 2 }))
    expect(formatType(null)).toBe('null')
    expect(formatType(42)).toBe('42')
  })
})

describe('makeField / defaultTypeDef / cloneField', () => {
  it('crea un campo con un tipo primitivo por defecto', () => {
    const f = makeField()
    expect(f.typeDef).toEqual({ kind: 'primitive', primitive: 'string' })
    expect(f.nullable).toBe(false)
  })

  it('defaultTypeDef cubre los siete kinds', () => {
    for (const kind of ['primitive', 'logical', 'array', 'map', 'record', 'enum', 'ref'] as const) {
      expect(defaultTypeDef(kind).kind).toBe(kind)
    }
  })

  it('clona un campo con un id nuevo', () => {
    const original = makeField()
    const copia = cloneField(original)
    expect(copia.id).not.toBe(original.id)
    expect(copia.typeDef).toEqual(original.typeDef)
  })

  it('clona recursivamente array, map y enum preservando la forma', () => {
    const arr = field('a', { kind: 'array', items: prim('string') })
    const map = field('m', { kind: 'map', values: prim('int') })
    const en  = field('e', { kind: 'enum', enumName: 'E', symbols: ['A'] })
    for (const f of [arr, map, en]) {
      const copia = cloneField(f)
      expect(copia.typeDef).toEqual(f.typeDef)
      expect(copia.typeDef).not.toBe(f.typeDef)
    }
    const enClonado = cloneField(en)
    if (enClonado.typeDef.kind === 'enum') {
      expect(enClonado.typeDef.symbols).not.toBe((en.typeDef as { symbols: string[] }).symbols)
    }
  })

  it('clona un record dándole un id nuevo a cada campo interno, pero preservando el resto', () => {
    const record = field('r', { kind: 'record', recordName: 'R', fields: [makeField()] })
    const copia = cloneField(record)
    expect(copia.typeDef.kind).toBe('record')
    if (copia.typeDef.kind === 'record' && record.typeDef.kind === 'record') {
      expect(copia.typeDef.fields[0].id).not.toBe(record.typeDef.fields[0].id)
      expect(copia.typeDef.fields[0].name).toBe(record.typeDef.fields[0].name)
      expect(copia.typeDef.fields[0].typeDef).toEqual(record.typeDef.fields[0].typeDef)
    }
  })
})

describe('namedInside / replaceNamedInside', () => {
  it('devuelve el record o enum directo', () => {
    const rec: TypeDef = { kind: 'record', recordName: 'R', fields: [] }
    expect(namedInside(rec)).toEqual(rec)
  })

  it('desciende por array y map hasta encontrar el nombrado', () => {
    const en: { kind: 'enum'; enumName: string; symbols: string[] } = { kind: 'enum', enumName: 'E', symbols: ['A'] }
    expect(namedInside({ kind: 'array', items: en })).toEqual(en)
    expect(namedInside({ kind: 'map', values: en })).toEqual(en)
  })

  it('devuelve null si no hay nada nombrado', () => {
    expect(namedInside(prim('string'))).toBeNull()
    expect(namedInside({ kind: 'ref', refName: 'X' })).toBeNull()
  })

  it('reemplaza preservando los wrappers array/map', () => {
    const nuevo: { kind: 'enum'; enumName: string; symbols: string[] } = { kind: 'enum', enumName: 'E2', symbols: ['B'] }
    const arr = replaceNamedInside({ kind: 'array', items: { kind: 'enum', enumName: 'E', symbols: ['A'] } }, nuevo)
    expect(arr).toEqual({ kind: 'array', items: nuevo })
    const map = replaceNamedInside({ kind: 'map', values: { kind: 'enum', enumName: 'E', symbols: ['A'] } }, nuevo)
    expect(map).toEqual({ kind: 'map', values: nuevo })
    expect(replaceNamedInside({ kind: 'record', recordName: 'R', fields: [] }, nuevo)).toBe(nuevo)
  })

  it('reemplazar en un tipo sin nombrado lo deja igual', () => {
    const t = prim('int')
    expect(replaceNamedInside(t, { kind: 'enum', enumName: 'E', symbols: [] })).toBe(t)
  })
})

describe('toAvroFields', () => {
  it('convierte primitivos', () => {
    expect(toAvroFields([field('x', prim('string'))])).toEqual([{ name: 'x', type: 'string' }])
  })

  it('descarta campos sin nombre', () => {
    expect(toAvroFields([field('  ', prim('string'))])).toEqual([])
  })

  it('envuelve en unión null cuando es nullable', () => {
    expect(toAvroFields([field('x', prim('int'), true)])).toEqual([{ name: 'x', type: ['null', 'int'] }])
  })

  it('logical no-decimal', () => {
    const t: TypeDef = { kind: 'logical', logical: 'timestamp-millis', precision: 10, scale: 2 }
    expect(toAvroFields([field('ts', t)])).toEqual([{ name: 'ts', type: { type: 'long', logicalType: 'timestamp-millis' } }])
  })

  it('logical decimal lleva precision y scale', () => {
    const t: TypeDef = { kind: 'logical', logical: 'decimal', precision: 8, scale: 3 }
    expect(toAvroFields([field('p', t)])).toEqual([{ name: 'p', type: { type: 'bytes', logicalType: 'decimal', precision: 8, scale: 3 } }])
  })

  it('array y map', () => {
    expect(toAvroFields([field('a', { kind: 'array', items: prim('string') })]))
      .toEqual([{ name: 'a', type: { type: 'array', items: 'string' } }])
    expect(toAvroFields([field('m', { kind: 'map', values: prim('int') })]))
      .toEqual([{ name: 'm', type: { type: 'map', values: 'int' } }])
  })

  it('record anidado', () => {
    const t: TypeDef = { kind: 'record', recordName: 'R', fields: [field('a', prim('string'))] }
    expect(toAvroFields([field('r', t)])).toEqual([
      { name: 'r', type: { type: 'record', name: 'R', fields: [{ name: 'a', type: 'string' }] } },
    ])
  })

  it('enum descarta símbolos vacíos', () => {
    const t: TypeDef = { kind: 'enum', enumName: 'E', symbols: ['A', ' ', 'B'] }
    expect(toAvroFields([field('e', t)])).toEqual([
      { name: 'e', type: { type: 'enum', name: 'E', symbols: ['A', 'B'] } },
    ])
  })

  it('ref', () => {
    expect(toAvroFields([field('r', { kind: 'ref', refName: 'Otro' })])).toEqual([{ name: 'r', type: 'Otro' }])
  })
})

describe('fromAvroFields (inversa de toAvroFields)', () => {
  it('no es array: vacío', () => {
    expect(fromAvroFields(undefined)).toEqual([])
    expect(fromAvroFields(null)).toEqual([])
  })

  it('primitivo simple', () => {
    const [f] = fromAvroFields([{ name: 'x', type: 'string' }])
    expect(f.typeDef).toEqual({ kind: 'primitive', primitive: 'string' })
    expect(f.nullable).toBe(false)
  })

  it('union null + tipo → nullable', () => {
    const [f] = fromAvroFields([{ name: 'x', type: ['null', 'int'] }])
    expect(f.nullable).toBe(true)
    expect(f.typeDef).toEqual({ kind: 'primitive', primitive: 'int' })
  })

  it('union con más de dos ramas cae a primitivo string, conservando nullable', () => {
    const [f] = fromAvroFields([{ name: 'x', type: ['null', 'int', 'string'] }])
    expect(f.nullable).toBe(true)
    expect(f.typeDef).toEqual({ kind: 'primitive', primitive: 'string' })
  })

  it('un string que no es primitivo es una referencia', () => {
    const [f] = fromAvroFields([{ name: 'x', type: 'Ubicacion' }])
    expect(f.typeDef).toEqual({ kind: 'ref', refName: 'Ubicacion' })
  })

  it('logicalType con precision/scale por defecto cuando faltan', () => {
    const [f] = fromAvroFields([{ name: 'p', type: { type: 'bytes', logicalType: 'decimal' } }])
    expect(f.typeDef).toEqual({ kind: 'logical', logical: 'decimal', precision: 10, scale: 2 })
  })

  it('array, map, record y enum', () => {
    const [a] = fromAvroFields([{ name: 'a', type: { type: 'array', items: 'int' } }])
    expect(a.typeDef).toEqual({ kind: 'array', items: { kind: 'primitive', primitive: 'int' } })

    const [m] = fromAvroFields([{ name: 'm', type: { type: 'map', values: 'string' } }])
    expect(m.typeDef).toEqual({ kind: 'map', values: { kind: 'primitive', primitive: 'string' } })

    const [r] = fromAvroFields([{ name: 'r', type: { type: 'record', name: 'R', fields: [{ name: 'x', type: 'int' }] } }])
    expect(r.typeDef.kind).toBe('record')

    const [e] = fromAvroFields([{ name: 'e', type: { type: 'enum', name: 'E', symbols: ['A', 'B'] } }])
    expect(e.typeDef).toEqual({ kind: 'enum', enumName: 'E', symbols: ['A', 'B'] })
  })

  it('enum sin symbols cae a un array con un string vacío', () => {
    const [e] = fromAvroFields([{ name: 'e', type: { type: 'enum', name: 'E' } }])
    expect((e.typeDef as { symbols: string[] }).symbols).toEqual([''])
  })

  it('un objeto con `type` string se resuelve recursivamente', () => {
    const [f] = fromAvroFields([{ name: 'x', type: { type: 'int' } }])
    expect(f.typeDef).toEqual({ kind: 'primitive', primitive: 'int' })
  })

  it('algo irreconocible cae a primitivo string', () => {
    const [f] = fromAvroFields([{ name: 'x', type: 42 }])
    expect(f.typeDef).toEqual({ kind: 'primitive', primitive: 'string' })
  })

  it('un elemento sin name/type no rompe', () => {
    const [f] = fromAvroFields([{}])
    expect(f.name).toBe('')
  })

  it('un elemento null o undefined en el array cae al objeto vacío, no rompe', () => {
    const [a] = fromAvroFields([null])
    expect(a.name).toBe('')
    const [b] = fromAvroFields([undefined])
    expect(b.name).toBe('')
  })

  it('un record sin `name` propio cae a un nombre vacío', () => {
    const [f] = fromAvroFields([{ name: 'r', type: { type: 'record', fields: [] } }])
    expect((f.typeDef as { recordName: string }).recordName).toBe('')
  })

  it('un enum sin `name` propio cae a un nombre vacío', () => {
    const [f] = fromAvroFields([{ name: 'e', type: { type: 'enum', symbols: ['A'] } }])
    expect((f.typeDef as { enumName: string }).enumName).toBe('')
  })

  it('decimal con precision y scale explícitos los conserva', () => {
    const [f] = fromAvroFields([{ name: 'p', type: { type: 'bytes', logicalType: 'decimal', precision: 12, scale: 4 } }])
    expect(f.typeDef).toEqual({ kind: 'logical', logical: 'decimal', precision: 12, scale: 4 })
  })

  it('hace roundtrip completo con toAvroFields', () => {
    const original = [
      { name: 'a', type: 'string' },
      { name: 'b', type: ['null', 'int'] },
      { name: 'c', type: { type: 'array', items: 'double' } },
      { name: 'd', type: { type: 'record', name: 'D', fields: [{ name: 'x', type: 'boolean' }] } },
    ]
    expect(toAvroFields(fromAvroFields(original))).toEqual(original)
  })
})

describe('collectRefScopes', () => {
  it('un campo no ve tipos declarados después de él', () => {
    const f1 = field('a', { kind: 'record', recordName: 'Primero', fields: [] })
    const f2 = field('b', { kind: 'record', recordName: 'Segundo', fields: [] })
    const scopes = collectRefScopes([f1, f2])
    expect(scopes.get(f1.id)).toEqual([])
    expect(scopes.get(f2.id)).toEqual(['Primero'])
  })

  it('recorre en profundidad los campos de un record anidado', () => {
    const inner = field('inner', { kind: 'record', recordName: 'Inner', fields: [] })
    const outer = field('outer', { kind: 'record', recordName: 'Outer', fields: [inner] })
    const scopes = collectRefScopes([outer])
    expect(scopes.get(inner.id)).toEqual(['Outer'])
  })

  it('desciende por array y map para registrar records/enums nombrados', () => {
    const arr = field('a', { kind: 'array', items: { kind: 'record', recordName: 'Item', fields: [] } })
    const next = field('b', prim('string'))
    const scopes = collectRefScopes([arr, next])
    expect(scopes.get(next.id)).toEqual(['Item'])
  })

  it('ignora tipos sin nombre (records/enums en blanco)', () => {
    const f1 = field('a', { kind: 'record', recordName: '', fields: [] })
    const f2 = field('b', prim('string'))
    expect(collectRefScopes([f1, f2]).get(f2.id)).toEqual([])
  })

  it('registra un enum declarado directamente en un campo (no envuelto en array/map)', () => {
    const f1 = field('a', { kind: 'enum', enumName: 'Estado', symbols: ['A'] })
    const f2 = field('b', prim('string'))
    expect(collectRefScopes([f1, f2]).get(f2.id)).toEqual(['Estado'])
  })

  it('desciende por un map cuyo valor es un record nombrado', () => {
    const f1 = field('m', { kind: 'map', values: { kind: 'record', recordName: 'Entrada', fields: [] } })
    const f2 = field('b', prim('string'))
    expect(collectRefScopes([f1, f2]).get(f2.id)).toEqual(['Entrada'])
  })
})

describe('validate', () => {
  it('un nombre y campos válidos no producen issues', () => {
    expect(validate('BiciDevuelta', [field('biciId', prim('string'))])).toEqual([])
  })

  it('rechaza el nombre vacío', () => {
    expect(validate('', []).some(i => i.path === 'nombre')).toBe(true)
  })

  it('rechaza un nombre con formato inválido', () => {
    expect(validate('123Malo', []).some(i => i.path === 'nombre')).toBe(true)
  })

  it('rechaza un campo sin nombre', () => {
    const f = field('', prim('string'))
    expect(validate('X', [f]).some(i => i.fieldId === f.id)).toBe(true)
  })

  it('rechaza un campo con nombre inválido', () => {
    expect(validate('X', [field('1malo', prim('string'))]).length).toBeGreaterThan(0)
  })

  it('rechaza campos duplicados en el mismo nivel', () => {
    const issues = validate('X', [field('a', prim('string')), field('a', prim('int'))])
    expect(issues.some(i => i.message.includes('duplicado'))).toBe(true)
  })

  it('un record sin nombre, o con nombre inválido, o sin campos', () => {
    expect(validate('X', [field('r', { kind: 'record', recordName: '', fields: [makeField()] })])
      .some(i => i.message.includes('necesita un nombre'))).toBe(true)
    expect(validate('X', [field('r', { kind: 'record', recordName: '1malo', fields: [makeField()] })])
      .length).toBeGreaterThan(0)
    expect(validate('X', [field('r', { kind: 'record', recordName: 'R', fields: [] })])
      .some(i => i.message.includes('no tiene campos'))).toBe(true)
  })

  it('un enum sin nombre, sin símbolos, con símbolo inválido o repetido', () => {
    expect(validate('X', [field('e', { kind: 'enum', enumName: '', symbols: ['A'] })])
      .some(i => i.message.includes('necesita un nombre'))).toBe(true)
    expect(validate('X', [field('e', { kind: 'enum', enumName: 'E', symbols: [] })])
      .some(i => i.message.includes('al menos un símbolo'))).toBe(true)
    expect(validate('X', [field('e', { kind: 'enum', enumName: '1mal', symbols: ['A'] })])
      .some(i => i.message.includes('no es válido'))).toBe(true)
    expect(validate('X', [field('e', { kind: 'enum', enumName: 'E', symbols: ['1mal'] })])
      .length).toBeGreaterThan(0)
    expect(validate('X', [field('e', { kind: 'enum', enumName: 'E', symbols: ['A', 'A'] })])
      .some(i => i.message.includes('repetidos'))).toBe(true)
  })

  it('decimal exige precision entera ≥1 y scale entera ≥0, con scale ≤ precision', () => {
    expect(validate('X', [field('p', { kind: 'logical', logical: 'decimal', precision: 0, scale: 0 })])
      .some(i => i.message.includes('precision'))).toBe(true)
    expect(validate('X', [field('p', { kind: 'logical', logical: 'decimal', precision: 5, scale: -1 })])
      .some(i => i.message.includes('scale'))).toBe(true)
    expect(validate('X', [field('p', { kind: 'logical', logical: 'decimal', precision: 2, scale: 5 })])
      .some(i => i.message.includes('no puede superar'))).toBe(true)
    expect(validate('X', [field('p', { kind: 'logical', logical: 'decimal', precision: 5, scale: 2 })])).toEqual([])
  })

  it('un logical no-decimal no valida precision/scale', () => {
    expect(validate('X', [field('t', { kind: 'logical', logical: 'timestamp-millis', precision: 0, scale: -1 })])).toEqual([])
  })

  it('una ref vacía pide elegir tipo; una ref a algo inexistente lo reporta', () => {
    expect(validate('X', [field('r', { kind: 'ref', refName: '' })])
      .some(i => i.message.includes('elegí el tipo'))).toBe(true)
    expect(validate('X', [field('r', { kind: 'ref', refName: 'NoExiste' })])
      .some(i => i.message.includes('no existe el tipo'))).toBe(true)
  })

  it('una ref a un tipo declarado en el mismo formulario no produce issue', () => {
    const record = field('a', { kind: 'record', recordName: 'A', fields: [] })
    const ref = field('b', { kind: 'ref', refName: 'A' })
    expect(validate('X', [record, ref]).some(i => i.message.includes('no existe'))).toBe(false)
  })

  it('array y map delegan la validación al tipo interno', () => {
    const arr = field('a', { kind: 'array', items: { kind: 'ref', refName: 'Nada' } })
    const map = field('m', { kind: 'map', values: { kind: 'ref', refName: 'Nada' } })
    expect(validate('X', [arr]).some(i => i.message.includes('no existe el tipo'))).toBe(true)
    expect(validate('X', [map]).some(i => i.message.includes('no existe el tipo'))).toBe(true)
  })

  it('dos tipos nombrados iguales, incluido el nombre raíz, se reportan como duplicados', () => {
    const issues = validate('X', [field('a', { kind: 'record', recordName: 'X', fields: [] })])
    expect(issues.some(i => i.message.includes('está definido 2 veces'))).toBe(true)
  })
})
