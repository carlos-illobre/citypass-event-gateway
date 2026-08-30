import { describe, it, expect } from 'vitest'
import type { DeadLetter } from '@/api/deadLetters'
import { decodePayload, reasonLabel, summarizeDeadLetters } from './deadLetters'

const b64 = (bytes: number[]) => btoa(String.fromCharCode(...bytes))

const carta = (over: Partial<DeadLetter>): DeadLetter => ({
  dlqId:                 'id-1',
  timestamp:             '2026-08-15T12:00:00.000Z',
  failureReason:         'DESERIALIZATION_ERROR',
  errorMessage:          'no se pudo leer',
  retryCount:            0,
  owner:                 'com.citypass.analitica',
  originalTopic:         'com.citypass.analitica.Algo',
  originalKey:           null,
  originalPayloadBase64: btoa('{}'),
  ...over,
})

describe('decodePayload', () => {
  it('reconoce JSON', () => {
    expect(decodePayload(btoa('{"a":1}'))).toEqual({ kind: 'json', value: { a: 1 } })
  })

  it('cae a texto cuando es UTF-8 válido pero no JSON', () => {
    expect(decodePayload(btoa('hola'))).toEqual({ kind: 'text', value: 'hola' })
  })

  it('reconoce binario cuando no es UTF-8 válido', () => {
    // Es el caso real: si el evento murió al deserializarse, lo que quedó son bytes de Avro.
    const resultado = decodePayload(b64([0x00, 0xff, 0xfe, 0x80]))
    expect(resultado).toEqual({ kind: 'binary', bytes: 4 })
  })

  it('devuelve `invalid` en vez de lanzar cuando el base64 está roto', () => {
    // `atob` explota con esto, y la pantalla que muestra payloads rotos no puede caerse por un
    // payload roto.
    expect(decodePayload('!!! no es base64 !!!')).toEqual({ kind: 'invalid' })
  })

  it('corta los payloads grandes en vez de intentar mostrarlos', () => {
    const grande = btoa('x'.repeat(200))
    expect(decodePayload(grande, 100)).toEqual({ kind: 'binary', bytes: 200 })
  })

  it('trata el vacío como texto vacío', () => {
    expect(decodePayload('')).toEqual({ kind: 'text', value: '' })
  })
})

describe('summarizeDeadLetters', () => {
  const mensajes = [
    carta({ dlqId: '1', failureReason: 'DESERIALIZATION_ERROR', retryCount: 0 }),
    carta({ dlqId: '2', failureReason: 'WEBHOOK_DELIVERY_FAILED', retryCount: 3 }),
    carta({ dlqId: '3', failureReason: 'WEBHOOK_DELIVERY_FAILED', retryCount: 3, originalTopic: 'otro.Topico' }),
  ]

  it('agrupa por motivo y por tópico', () => {
    const s = summarizeDeadLetters(mensajes)
    expect(s.total).toBe(3)
    expect(s.byReason[0]).toEqual({ key: 'WEBHOOK_DELIVERY_FAILED', count: 2 })
    expect(s.byTopic).toHaveLength(2)
  })

  it('cuenta cuántos agotaron los tres reintentos', () => {
    expect(summarizeDeadLetters(mensajes).exhausted).toBe(2)
  })

  it('no rompe con la cola vacía', () => {
    expect(summarizeDeadLetters([])).toEqual({ total: 0, byReason: [], byTopic: [], exhausted: 0 })
  })
})

describe('reasonLabel', () => {
  it('traduce los dos motivos que el gateway sabe producir', () => {
    expect(reasonLabel('DESERIALIZATION_ERROR')).toBe('No se pudo deserializar')
    expect(reasonLabel('WEBHOOK_DELIVERY_FAILED')).toBe('Falló la entrega del webhook')
  })

  it('deja pasar un motivo desconocido tal cual', () => {
    expect(reasonLabel('ALGO_NUEVO')).toBe('ALGO_NUEVO')
  })
})
