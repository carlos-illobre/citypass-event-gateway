import { api } from '@/config'
import { apiFetch } from './client'

/**
 * Una entrada de la cola de fallidos.
 *
 * `owner` es el namespace dueño del tópico cuando la falla fue de deserialización, o el
 * dueño de la suscripción cuando la falla fue de entrega de webhook — dos conceptos
 * distintos que comparten el mismo campo.
 */
export type DeadLetter = {
  dlqId:                string
  timestamp:            string
  failureReason:        'DESERIALIZATION_ERROR' | 'WEBHOOK_DELIVERY_FAILED'
  errorMessage:         string
  retryCount:           number
  owner:                string
  originalTopic:        string
  originalKey:          string | null
  originalPayloadBase64: string
}

export type DeadLettersResponse = { topic: string; returned: number; messages: DeadLetter[] }

export const deadLetters = {
  /** Filtrado por el gateway a `owner == namespace` del token — nunca el DLQ entero. */
  list: (token: string, limit = 50, signal?: AbortSignal): Promise<DeadLettersResponse> =>
    apiFetch<DeadLettersResponse>(`${api.gateway.deadLetters}?limit=${limit}`, { token, signal }),
}
