import { describe, it, expect } from 'vitest'
import { flattenSchema, typeLabel } from './avro'

describe('typeLabel', () => {
  it('describe los tipos simples', () => {
    expect(typeLabel('string')).toBe('string')
  })

  it('lee una unión con null como el tipo que no es null', () => {
    // Es cómo Avro dice «opcional». Mostrarlo como `null | string` sería fiel al JSON y peor de
    // leer: lo opcional se marca aparte.
    expect(typeLabel(['null', 'string'])).toBe('string')
  })

  it('conserva las uniones reales de más de un tipo', () => {
    expect(typeLabel(['string', 'int'])).toBe('string | int')
  })

  it('describe arrays, maps, enums y records', () => {
    expect(typeLabel({ type: 'array', items: 'string' })).toBe('array<string>')
    expect(typeLabel({ type: 'map', values: 'int' })).toBe('map<int>')
    expect(typeLabel({ type: 'enum', name: 'Estado' })).toBe('enum Estado')
    expect(typeLabel({ type: 'record', name: 'Direccion' })).toBe('record Direccion')
  })

  it('muestra el tipo lógico cuando lo hay', () => {
    expect(typeLabel({ type: 'long', logicalType: 'timestamp-millis' }))
      .toBe('long (timestamp-millis)')
  })

  it('tolera un enum o un record sin nombre', () => {
    // Avro exige el nombre, pero el visor no puede caerse con un `undefined` si el esquema
    // llega mal formado: mostrar «enum» pelado es mejor que «enum undefined».
    expect(typeLabel({ type: 'enum' })).toBe('enum')
    expect(typeLabel({ type: 'record' })).toBe('record')
  })

  it('no rompe con un tipo que no reconoce', () => {
    expect(typeLabel(null)).toBe('desconocido')
    expect(typeLabel(42)).toBe('desconocido')
    expect(typeLabel({})).toBe('desconocido')
  })
})

describe('flattenSchema', () => {
  const schema = {
    type: 'record',
    name: 'BiciDevuelta',
    fields: [
      { name: 'biciId', type: 'string', doc: 'Identificador de la bici' },
      { name: 'estacion', type: ['null', 'string'], default: null },
      {
        name: 'origen',
        type: { type: 'record', name: 'Estacion', fields: [{ name: 'calle', type: 'string' }] },
      },
    ],
  }

  it('lista los campos de primer nivel con su tipo', () => {
    const filas = flattenSchema(schema)
    expect(filas[0]).toMatchObject({ name: 'biciId', type: 'string', depth: 0, nullable: false })
  })

  it('marca los opcionales y los que tienen default', () => {
    const estacion = flattenSchema(schema).find(f => f.name === 'estacion')
    expect(estacion).toMatchObject({ nullable: true, hasDefault: true })
  })

  it('conserva la documentación del campo', () => {
    expect(flattenSchema(schema)[0].doc).toBe('Identificador de la bici')
  })

  it('baja a los records anidados y arma la ruta con puntos', () => {
    const anidado = flattenSchema(schema).find(f => f.path === 'origen.calle')
    expect(anidado).toMatchObject({ name: 'calle', depth: 1 })
  })

  it('entra en los records que están dentro de arrays', () => {
    const conArray = {
      type: 'record', name: 'X',
      fields: [{
        name: 'items',
        type: { type: 'array', items: { type: 'record', name: 'Item', fields: [{ name: 'sku', type: 'string' }] } },
      }],
    }
    expect(flattenSchema(conArray).map(f => f.path)).toEqual(['items', 'items.sku'])
  })

  it('entra en los records que están dentro de maps', () => {
    const conMap = {
      type: 'record', name: 'X',
      fields: [{
        name: 'porZona',
        type: { type: 'map', values: { type: 'record', name: 'Zona', fields: [{ name: 'nombre', type: 'string' }] } },
      }],
    }
    expect(flattenSchema(conMap).map(f => f.path)).toEqual(['porZona', 'porZona.nombre'])
  })

  it('entra en los records que están dentro de una unión opcional', () => {
    // `["null", {record}]` es lo que produce Avro para un record opcional, que es de lo más
    // común en los contratos de los otros grupos.
    const conUnion = {
      type: 'record', name: 'X',
      fields: [{
        name: 'direccion',
        type: ['null', { type: 'record', name: 'Direccion', fields: [{ name: 'calle', type: 'string' }] }],
      }],
    }
    expect(flattenSchema(conUnion).map(f => f.path)).toEqual(['direccion', 'direccion.calle'])
  })

  it('devuelve null cuando la unión no trae ningún record adentro', () => {
    const sinRecord = {
      type: 'record', name: 'X',
      fields: [{ name: 'valor', type: ['null', 'string', 'int'] }],
    }
    expect(flattenSchema(sinRecord).map(f => f.path)).toEqual(['valor'])
  })

  it('no baja por un tipo que no es ni record, ni array, ni map', () => {
    const conEnum = {
      type: 'record', name: 'X',
      fields: [{ name: 'estado', type: { type: 'enum', name: 'Estado', symbols: ['A', 'B'] } }],
    }
    expect(flattenSchema(conEnum).map(f => f.path)).toEqual(['estado'])
  })

  it('devuelve vacío para algo que no es un record', () => {
    expect(flattenSchema('string')).toEqual([])
    expect(flattenSchema(null)).toEqual([])
    expect(flattenSchema({ type: 'record', name: 'X', fields: [] })).toEqual([])
  })

  it('corta ante un esquema recursivo en vez de colgarse', () => {
    // El gateway no impide registrar un record que se referencia a sí mismo.
    const recursivo: Record<string, unknown> = { type: 'record', name: 'Nodo', fields: [] }
    recursivo.fields = [{ name: 'hijo', type: recursivo }]
    expect(flattenSchema(recursivo, 3).length).toBeLessThanOrEqual(5)
  })

  it('ignora las entradas de `fields` que no son objetos', () => {
    const raro = { type: 'record', name: 'X', fields: ['no soy un campo', { name: 'ok', type: 'string' }] }
    expect(flattenSchema(raro).map(f => f.name)).toEqual(['ok'])
  })

  it('tolera un campo sin nombre en vez de mostrar «undefined»', () => {
    const sinNombre = { type: 'record', name: 'X', fields: [{ type: 'string' }] }
    expect(flattenSchema(sinNombre).map(f => f.name)).toEqual([''])
  })
})
