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
  failureReason:        'DESERIALIZATION_ERROR' | 'WEBHOOK_DELIVERY_FAILED' | 'WEBHOOK_SILENCED'
  errorMessage:         string
  retryCount:           number
  owner:                string
  originalTopic:        string
  originalKey:          string | null
  originalPayloadBase64: string
}

export type DeadLettersResponse = { topic: string; returned: number; messages: DeadLetter[] }

/**
 * Respuesta de un reintento. El dispatcher contesta 200 con `entregado`, o 502 con `fallido`
 * cuando el destino vuelve a fallar — ese 502 llega a `apiFetch` como error, no acá.
 */
export type RetryResult = { dlqId: string; estado: 'entregado' | 'fallido'; callbackUrl: string }

export const deadLetters = {
  /** Filtrado por el gateway a `owner == namespace` del token — nunca el DLQ entero. */
  list: (token: string, limit = 50, signal?: AbortSignal): Promise<DeadLettersResponse> =>
    apiFetch<DeadLettersResponse>(`${api.gateway.deadLetters}?limit=${limit}`, { token, signal }),

  /**
   * Vuelve a entregar una entrada por webhook. El destino lo resuelve el dispatcher con la
   * suscripción **vigente**, no con la URL que quedó guardada al fallar. Si se entrega, la
   * entrada pasa a resuelta y deja de aparecer en `list`.
   */
  retry: (token: string, dlqId: string): Promise<RetryResult> =>
    apiFetch<RetryResult>(`${api.gateway.deadLetters}/${encodeURIComponent(dlqId)}/reintentar`, { method: 'POST', token }),
}
