import { describe, it, expect } from 'vitest'
import {
  collectNamed, resolveType, emptyValue, newItem, newEntry, toPayload, sampleValue,
  type NamedTypes, type ValueNode,
} from './value'
import type { RawField } from './avro'

describe('collectNamed', () => {
  it('indexa records y enums por nombre simple y completo', () => {
    const type = { type: 'record', name: 'Coord', namespace: 'com.citypass', fields: [] }
    const named = collectNamed(type)
    expect(named.get('Coord')).toBe(type)
    expect(named.get('com.citypass.Coord')).toBe(type)
  })

  it('desciende por fields, items y values', () => {
    const inner = { type: 'record', name: 'Inner', fields: [] }
    const type = {
      type: 'record', name: 'Outer',
      fields: [
        { name: 'a', type: { type: 'array', items: inner } },
        { name: 'b', type: { type: 'map', values: 'string' } },
      ],
    }
    const named = collectNamed(type)
    expect(named.get('Inner')).toBe(inner)
  })

  it('ignora primitivos y valores no-objeto', () => {
    expect(collectNamed('string').size).toBe(0)
    expect(collectNamed(null).size).toBe(0)
    expect(collectNamed(42).size).toBe(0)
  })

  it('recorre un array de tipos (unión)', () => {
    const record = { type: 'record', name: 'X', fields: [] }
    expect(collectNamed(['null', record]).get('X')).toBe(record)
  })
})

describe('resolveType', () => {
  const named: NamedTypes = new Map()

  it('primitivos', () => {
    expect(resolveType('string', named)).toEqual({ shape: { kind: 'text' }, nullable: false })
    expect(resolveType('int', named)).toEqual({ shape: { kind: 'number', integer: true }, nullable: false })
    expect(resolveType('boolean', named)).toEqual({ shape: { kind: 'boolean' }, nullable: false })
  })

  it('unión con null es nullable', () => {
    const r = resolveType(['null', 'int'], named)
    expect(r.nullable).toBe(true)
    expect(r.shape).toEqual({ kind: 'number', integer: true })
  })

  it('unión de más de dos ramas no nulas cae a json', () => {
    const r = resolveType(['string', 'int'], named)
    expect(r.shape).toEqual({ kind: 'json' })
  })

  it('una referencia sin destino cae a json', () => {
    expect(resolveType('NoExiste', named).shape).toEqual({ kind: 'json' })
  })

  it('una referencia con namespace que tampoco resuelve por su nombre simple cae a json', () => {
    expect(resolveType('com.citypass.NoExiste', named).shape).toEqual({ kind: 'json' })
  })

  it('un record sin `name` ni `fields` propios no rompe: caen a vacío', () => {
    expect(resolveType({ type: 'record' }, named).shape).toEqual({ kind: 'record', name: '', fields: [] })
  })

  it('un enum sin `symbols` propio cae a lista vacía', () => {
    expect(resolveType({ type: 'enum' }, named).shape).toEqual({ kind: 'enum', symbols: [] })
  })

  it('resuelve una referencia por nombre completo o simple', () => {
    const record = { type: 'record', name: 'Coord', fields: [] }
    const withNs = collectNamed({ type: 'record', name: 'Coord', namespace: 'ns', fields: [] })
    expect(resolveType('Coord', new Map([['Coord', record]])).shape.kind).toBe('record')
    expect(resolveType('ns.Coord', withNs).shape.kind).toBe('record')
  })

  it('tipos lógicos', () => {
    expect(resolveType({ type: 'long', logicalType: 'timestamp-millis' }, named).shape).toEqual({ kind: 'datetime', micros: false })
    expect(resolveType({ type: 'string', logicalType: 'uuid' }, named).shape).toEqual({ kind: 'text' })
  })

  it('record, enum, array, map', () => {
    expect(resolveType({ type: 'record', name: 'R', fields: [{ name: 'x', type: 'int' }] }, named).shape)
      .toEqual({ kind: 'record', name: 'R', fields: [{ name: 'x', type: 'int' }] })
    expect(resolveType({ type: 'enum', name: 'E', symbols: ['A'] }, named).shape)
      .toEqual({ kind: 'enum', symbols: ['A'] })
    expect(resolveType({ type: 'array', items: 'string' }, named).shape).toEqual({ kind: 'array', items: 'string' })
    expect(resolveType({ type: 'map', values: 'int' }, named).shape).toEqual({ kind: 'map', values: 'int' })
  })

  it('null, no-objeto y objeto sin type reconocible caen a json', () => {
    expect(resolveType(null, named).shape).toEqual({ kind: 'json' })
    expect(resolveType(42, named).shape).toEqual({ kind: 'json' })
    expect(resolveType({}, named).shape).toEqual({ kind: 'json' })
  })

  it('un objeto con type string se resuelve recursivamente', () => {
    expect(resolveType({ type: 'int' }, named).shape).toEqual({ kind: 'number', integer: true })
  })
})

describe('emptyValue / newItem / newEntry', () => {
  const named: NamedTypes = new Map()

  it('escalar vacío', () => {
    expect(emptyValue('string', named)).toEqual({ kind: 'scalar', raw: '' })
  })

  it('nullable envuelve con isNull true', () => {
    expect(emptyValue(['null', 'string'], named)).toEqual({ kind: 'nullable', isNull: true, inner: { kind: 'scalar', raw: '' } })
  })

  it('record vacío inicializa cada campo', () => {
    const v = emptyValue({ type: 'record', name: 'R', fields: [{ name: 'x', type: 'int' }] }, named)
    expect(v).toEqual({ kind: 'record', fields: { x: { kind: 'scalar', raw: '' } } })
  })

  it('array y map vacíos', () => {
    expect(emptyValue({ type: 'array', items: 'string' }, named)).toEqual({ kind: 'array', items: [] })
    expect(emptyValue({ type: 'map', values: 'string' }, named)).toEqual({ kind: 'map', entries: [] })
  })

  it('newItem y newEntry generan ids únicos', () => {
    const a = newItem('string', named)
    const b = newItem('string', named)
    expect(a.id).not.toBe(b.id)
    const e = newEntry('string', named)
    expect(e.key).toBe('')
  })
})

describe('toPayload', () => {
  const fields: RawField[] = [{ name: 'nombre', type: 'string' }, { name: 'edad', type: 'int' }]

  it('convierte campos escalares', () => {
    const values = { nombre: { kind: 'scalar', raw: 'Ana' } as ValueNode, edad: { kind: 'scalar', raw: '30' } as ValueNode }
    const { data, issues } = toPayload(fields, values, new Map())
    expect(data).toEqual({ nombre: 'Ana', edad: 30 })
    expect(issues).toEqual([])
  })

  it('un campo faltante en `values` se convierte a null', () => {
    const { data } = toPayload(fields, {}, new Map())
    expect(data).toEqual({ nombre: null, edad: null })
  })

  it('nullable con isNull true da null', () => {
    const values = { nombre: { kind: 'nullable', isNull: true, inner: { kind: 'scalar', raw: 'x' } } as ValueNode }
    const { data } = toPayload([{ name: 'nombre', type: ['null', 'string'] }], values, new Map())
    expect(data.nombre).toBeNull()
  })

  it('nullable con isNull false delega al interno', () => {
    const values = { nombre: { kind: 'nullable', isNull: false, inner: { kind: 'scalar', raw: 'x' } } as ValueNode }
    const { data } = toPayload([{ name: 'nombre', type: ['null', 'string'] }], values, new Map())
    expect(data.nombre).toBe('x')
  })

  it('record: convierte cada campo interno', () => {
    const rtype = { type: 'record', name: 'R', fields: [{ name: 'x', type: 'int' }] }
    const values = { r: { kind: 'record', fields: { x: { kind: 'scalar', raw: '5' } } } as ValueNode }
    const { data } = toPayload([{ name: 'r', type: rtype }], values, new Map())
    expect(data.r).toEqual({ x: 5 })
  })

  it('record: un campo del shape sin nombre se salta, en vez de romper', () => {
    const rtype = { type: 'record', name: 'R', fields: [{ type: 'string' }, { name: 'x', type: 'int' }] }
    const values = { r: { kind: 'record', fields: { x: { kind: 'scalar', raw: '5' } } } as ValueNode }
    const { data } = toPayload([{ name: 'r', type: rtype }], values, new Map())
    expect(data.r).toEqual({ x: 5 })
  })

  it('record: si el nodo no calza con el shape, da null', () => {
    const values = { r: { kind: 'scalar', raw: 'x' } as ValueNode }
    const { data } = toPayload([{ name: 'r', type: { type: 'record', name: 'R', fields: [] } }], values, new Map())
    expect(data.r).toBeNull()
  })

  it('record: nodo record pero el tipo resuelto ya no es record, da null', () => {
    const values = { r: { kind: 'record', fields: {} } as ValueNode }
    const { data } = toPayload([{ name: 'r', type: 'string' }], values, new Map())
    expect(data.r).toBeNull()
  })

  it('array: convierte cada ítem', () => {
    const values = {
      a: { kind: 'array', items: [{ id: '1', node: { kind: 'scalar', raw: '1' } }, { id: '2', node: { kind: 'scalar', raw: '2' } }] } as ValueNode,
    }
    const { data } = toPayload([{ name: 'a', type: { type: 'array', items: 'int' } }], values, new Map())
    expect(data.a).toEqual([1, 2])
  })

  it('array: si el shape esperado no es array, da []', () => {
    // El nodo SÍ es un árbol de array —lo que puede pasar si el schema cambió de forma
    // entre que se cargó el valor y se convierte— pero el tipo resuelto ya no lo es.
    const values = { a: { kind: 'array', items: [{ id: '1', node: { kind: 'scalar', raw: '1' } }] } as ValueNode }
    const { data } = toPayload([{ name: 'a', type: 'string' }], values, new Map())
    expect(data.a).toEqual([])
  })

  it('map: convierte entradas, reporta clave vacía o repetida', () => {
    const values = {
      m: {
        kind: 'map',
        entries: [
          { id: '1', key: 'a', node: { kind: 'scalar', raw: '1' } },
          { id: '2', key: '', node: { kind: 'scalar', raw: '2' } },
          { id: '3', key: 'a', node: { kind: 'scalar', raw: '3' } },
        ],
      } as ValueNode,
    }
    const { data, issues } = toPayload([{ name: 'm', type: { type: 'map', values: 'int' } }], values, new Map())
    expect(data.m).toEqual({ a: 1 })
    expect(issues.some(i => i.message.includes('sin clave'))).toBe(true)
    expect(issues.some(i => i.message.includes('repetida'))).toBe(true)
  })

  it('map: si el shape esperado no es map, da {}', () => {
    const values = { m: { kind: 'map', entries: [{ id: '1', key: 'a', node: { kind: 'scalar', raw: '1' } }] } as ValueNode }
    const { data } = toPayload([{ name: 'm', type: 'string' }], values, new Map())
    expect(data.m).toEqual({})
  })

  it('descarta campos sin nombre', () => {
    const { data } = toPayload([{ name: '', type: 'string' }], {}, new Map())
    expect(data).toEqual({})
  })

  describe('escalares', () => {
    const conv = (shapeType: unknown, raw: string) =>
      toPayload([{ name: 'x', type: shapeType }], { x: { kind: 'scalar', raw } }, new Map())

    it('texto pasa tal cual', () => { expect(conv('string', 'hola').data.x).toBe('hola') })

    it('boolean', () => {
      expect(conv('boolean', 'true').data.x).toBe(true)
      expect(conv('boolean', 'false').data.x).toBe(false)
    })

    it('enum válido, vacío e inválido', () => {
      const enumType = { type: 'enum', name: 'E', symbols: ['A', 'B'] }
      expect(toPayload([{ name: 'x', type: enumType }], { x: { kind: 'scalar', raw: 'A' } }, new Map()).data.x).toBe('A')
      const vacio = toPayload([{ name: 'x', type: enumType }], { x: { kind: 'scalar', raw: '' } }, new Map())
      expect(vacio.data.x).toBeNull()
      expect(vacio.issues[0].message).toContain('elegí un valor')
      const invalido = toPayload([{ name: 'x', type: enumType }], { x: { kind: 'scalar', raw: 'Z' } }, new Map())
      expect(invalido.issues[0].message).toContain('no es un símbolo válido')
    })

    it('número entero y decimal, vacío e inválido', () => {
      expect(conv('int', '42').data.x).toBe(42)
      expect(conv('double', '3.5').data.x).toBe(3.5)
      const vacio = conv('int', '  ')
      expect(vacio.data.x).toBeNull()
      expect(vacio.issues[0].message).toContain('falta completar')
      const invalido = conv('int', 'abc')
      expect(invalido.issues[0].message).toContain('no es un número válido')
    })

    it('date: convierte a días desde epoch, vacío e inválido', () => {
      const dateType = { type: 'int', logicalType: 'date' }
      expect(toPayload([{ name: 'x', type: dateType }], { x: { kind: 'scalar', raw: '2026-08-12' } }, new Map()).data.x).toBe(20677)
      expect(toPayload([{ name: 'x', type: dateType }], { x: { kind: 'scalar', raw: '' } }, new Map()).issues[0].message).toContain('falta completar la fecha')
      expect(toPayload([{ name: 'x', type: dateType }], { x: { kind: 'scalar', raw: 'no-es-fecha' } }, new Map()).issues[0].message).toContain('no es válida')
    })

    it('time: convierte a milisegundos desde medianoche, con y sin segundos, vacío e inválido', () => {
      const timeType = { type: 'int', logicalType: 'time-millis' }
      expect(toPayload([{ name: 'x', type: timeType }], { x: { kind: 'scalar', raw: '01:00' } }, new Map()).data.x).toBe(3_600_000)
      expect(toPayload([{ name: 'x', type: timeType }], { x: { kind: 'scalar', raw: '01:00:30' } }, new Map()).data.x).toBe(3_630_000)
      expect(toPayload([{ name: 'x', type: timeType }], { x: { kind: 'scalar', raw: '' } }, new Map()).issues[0].message).toContain('falta completar la hora')
      expect(toPayload([{ name: 'x', type: timeType }], { x: { kind: 'scalar', raw: 'ab:cd' } }, new Map()).issues[0].message).toContain('la hora no es válida')
    })

    it('datetime millis y micros, vacío e inválido', () => {
      const ms = { type: 'long', logicalType: 'timestamp-millis' }
      const us = { type: 'long', logicalType: 'timestamp-micros' }
      const raw = '2026-08-12T15:05:00.000Z'
      const millis = Date.parse(raw)
      expect(toPayload([{ name: 'x', type: ms }], { x: { kind: 'scalar', raw } }, new Map()).data.x).toBe(millis)
      expect(toPayload([{ name: 'x', type: us }], { x: { kind: 'scalar', raw } }, new Map()).data.x).toBe(millis * 1000)
      expect(toPayload([{ name: 'x', type: ms }], { x: { kind: 'scalar', raw: '' } }, new Map()).issues[0].message).toContain('falta completar la fecha y hora')
      expect(toPayload([{ name: 'x', type: ms }], { x: { kind: 'scalar', raw: 'no-es-fecha' } }, new Map()).issues[0].message).toContain('no son válidas')
    })

    it('json (fixed, uniones de 3+ ramas, y todo lo demás que no se sabe editar): vacío da null, válido parsea, inválido reporta', () => {
      // Una unión de más de dos ramas no nulas resuelve a shape 'json' (resolveType).
      const union3 = ['int', 'string', 'boolean']
      expect(conv(union3, '').data.x).toBeNull()
      expect(conv(union3, '{"a":1}').data.x).toEqual({ a: 1 })
      expect(conv(union3, '{ mal }').issues[0].message).toContain('el JSON no es válido')
    })
  })
})

describe('sampleValue', () => {
  const named: NamedTypes = new Map()

  it('escalares de ejemplo por shape', () => {
    expect(sampleValue('boolean', named)).toEqual({ kind: 'scalar', raw: 'true' })
    expect(sampleValue('int', named)).toEqual({ kind: 'scalar', raw: '42' })
    expect(sampleValue('double', named)).toEqual({ kind: 'scalar', raw: '7.25' })
    expect(sampleValue({ type: 'int', logicalType: 'date' }, named)).toEqual({ kind: 'scalar', raw: '2026-08-12' })
    expect(sampleValue({ type: 'int', logicalType: 'time-millis' }, named)).toEqual({ kind: 'scalar', raw: '15:05' })
    expect(sampleValue({ type: 'long', logicalType: 'timestamp-millis' }, named)).toEqual({ kind: 'scalar', raw: '2026-08-12T15:05' })
    expect(sampleValue('string', named)).toEqual({ kind: 'scalar', raw: 'texto' })
  })

  it('enum de ejemplo usa el primer símbolo, o vacío si no hay', () => {
    expect(sampleValue({ type: 'enum', name: 'E', symbols: ['A', 'B'] }, named)).toEqual({ kind: 'scalar', raw: 'A' })
    expect(sampleValue({ type: 'enum', name: 'E', symbols: [] }, named)).toEqual({ kind: 'scalar', raw: '' })
  })

  it('json de ejemplo es {}', () => {
    expect(sampleValue({}, named)).toEqual({ kind: 'scalar', raw: '{}' })
  })

  it('record de ejemplo rellena cada campo', () => {
    const v = sampleValue({ type: 'record', name: 'R', fields: [{ name: 'x', type: 'int' }] }, named)
    expect(v).toEqual({ kind: 'record', fields: { x: { kind: 'scalar', raw: '42' } } })
  })

  it('array y map de ejemplo traen un elemento', () => {
    const arr = sampleValue({ type: 'array', items: 'string' }, named) as { kind: 'array'; items: { node: ValueNode }[] }
    expect(arr.items).toHaveLength(1)
    const map = sampleValue({ type: 'map', values: 'int' }, named) as { kind: 'map'; entries: { key: string }[] }
    expect(map.entries[0].key).toBe('clave')
  })

  it('nullable de ejemplo no está en null', () => {
    const v = sampleValue(['null', 'string'], named)
    expect(v).toMatchObject({ kind: 'nullable', isNull: false })
  })
})
