// Vocabulario Avro que ofrece el autocompletado del editor JSON.
//
// Las plantillas usan la sintaxis de marcadores de CodeMirror (`${nombre}`), que su
// motor de snippets interpreta para dejar el cursor donde hay que completar.

export type FieldSnippet = {
  label:    string
  detail:   string
  template: string
}

export const FIELD_SNIPPETS: FieldSnippet[] = [
  {
    label:  'texto',
    detail: 'Campo de texto',
    template: '{ "name": "${nombre}", "type": "string" }',
  },
  {
    label:  'número',
    detail: 'Campo numérico entero',
    template: '{ "name": "${nombre}", "type": "int" }',
  },
  {
    label:  'sí/no',
    detail: 'Campo booleano',
    template: '{ "name": "${nombre}", "type": "boolean" }',
  },
  {
    label:  'opcional',
    detail: 'Campo que puede venir sin valor',
    template: '{ "name": "${nombre}", "type": ["null", "string"] }',
  },
  {
    label:  'fecha y hora',
    detail: 'Instante: long con logicalType timestamp-millis',
    template: '{ "name": "${nombre}", "type": { "type": "long", "logicalType": "timestamp-millis" } }',
  },
  {
    label:  'decimal',
    detail: 'Monto exacto: bytes con logicalType decimal',
    template: '{ "name": "${nombre}", "type": { "type": "bytes", "logicalType": "decimal", "precision": 9, "scale": 2 } }',
  },
  {
    label:  'record',
    detail: 'Objeto anidado con sus propios campos',
    template: `{
  "name": "\${nombre}",
  "type": {
    "type": "record",
    "name": "\${Ubicacion}",
    "fields": [
      { "name": "lat", "type": "double" },
      { "name": "lon", "type": "double" }
    ]
  }
}`,
  },
  {
    label:  'lista',
    detail: 'Array de valores',
    template: '{ "name": "${nombre}", "type": { "type": "array", "items": "string" } }',
  },
  {
    label:  'lista de records',
    detail: 'Array de objetos anidados',
    template: `{
  "name": "\${nombre}",
  "type": {
    "type": "array",
    "items": {
      "type": "record",
      "name": "\${Tramo}",
      "fields": [
        { "name": "km", "type": "double" }
      ]
    }
  }
}`,
  },
  {
    label:  'mapa',
    detail: 'Diccionario de clave/valor',
    template: '{ "name": "${nombre}", "type": { "type": "map", "values": "string" } }',
  },
  {
    label:  'enum',
    detail: 'Conjunto cerrado de símbolos',
    template: `{
  "name": "\${nombre}",
  "type": {
    "type": "enum",
    "name": "\${Estado}",
    "symbols": ["ACTIVO", "INACTIVO"]
  }
}`,
  },
]

/** Tipos primitivos de Avro, para completar el valor de `type`. */
export const PRIMITIVE_NAMES = [
  'string', 'int', 'long', 'float', 'double', 'boolean', 'bytes', 'null',
]

/** Tipos compuestos: válidos como valor de `type` dentro de una definición. */
export const COMPLEX_NAMES = ['record', 'array', 'map', 'enum', 'fixed']

export const LOGICAL_NAMES = [
  'date', 'time-millis', 'timestamp-millis', 'timestamp-micros', 'uuid', 'decimal',
]

/** Claves válidas dentro de un objeto, según qué se esté definiendo. */
export const KEY_NAMES: Record<string, string[]> = {
  record: ['name', 'namespace', 'doc', 'fields'],
  enum:   ['name', 'symbols', 'doc', 'default'],
  array:  ['items'],
  map:    ['values'],
  fixed:  ['name', 'size'],
  bytes:  ['logicalType', 'precision', 'scale'],
  field:  ['name', 'type', 'doc', 'default'],
}
