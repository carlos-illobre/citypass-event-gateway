import type { DeadLetter } from '@/api/deadLetters'
import { tallyBy, type Tally } from './tally'

export type DecodedPayload =
  | { kind: 'json';   value: unknown }
  | { kind: 'text';   value: string }
  | { kind: 'binary'; bytes: number }
  | { kind: 'invalid' }

/**
 * El payload original de la cola de fallidos viaja en base64 porque puede no ser texto: si el
 * evento murió al deserializarse, lo que quedó son bytes de Avro, no JSON.
 *
 * Se intenta en orden —JSON, texto, bytes— y cada escalón es un modo de mostrarlo distinto, no
 * un error. La función es total: nunca lanza. `atob` explota con base64 inválido, y un payload
 * roto no puede tumbar la pantalla que existe justamente para mostrar payloads rotos.
 */
export function decodePayload(base64: string, maxBytes = 64 * 1024): DecodedPayload {
  let bytes: Uint8Array
  try {
    const binary = atob(base64)
    bytes = Uint8Array.from(binary, c => c.charCodeAt(0))
  } catch {
    return { kind: 'invalid' }
  }

  if (bytes.length > maxBytes) return { kind: 'binary', bytes: bytes.length }

  let text: string
  try {
    // `fatal` hace que un byte que no sea UTF-8 válido lance en vez de dejar un rombo: es la
    // única forma de distinguir texto real de bytes de Avro que casualmente decodifican.
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    return { kind: 'binary', bytes: bytes.length }
  }

  try {
    return { kind: 'json', value: JSON.parse(text) }
  } catch {
    return { kind: 'text', value: text }
  }
}

export type DeadLetterSummary = {
  total:     number
  byReason:  Tally[]
  byTopic:   Tally[]
  /** Cuántos agotaron los tres reintentos. Es la señal de que hay algo roto, no lento. */
  exhausted: number
}

export function summarizeDeadLetters(messages: readonly DeadLetter[]): DeadLetterSummary {
  return {
    total:     messages.length,
    byReason:  tallyBy(messages, m => m.failureReason),
    byTopic:   tallyBy(messages, m => m.originalTopic),
    exhausted: messages.filter(m => m.retryCount >= 3).length,
  }
}

/** Etiqueta legible para los dos motivos que el gateway sabe producir. */
export function reasonLabel(reason: string): string {
  if (reason === 'DESERIALIZATION_ERROR') return 'No se pudo deserializar'
  if (reason === 'WEBHOOK_DELIVERY_FAILED') return 'Falló la entrega del webhook'
  return reason
}
