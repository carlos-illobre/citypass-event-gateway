import { config } from '@/config'
import { apiFetch } from './client'

/**
 * Cliente del webhook-dispatcher.
 *
 * Está separado de `gateway.ts` por el mismo motivo por el que los servicios están
 * separados: es otro proceso, con otra URL base, que puede no estar desplegado. Meterlo en
 * el cliente del gateway haría que una pantalla pudiera llamarlo sin darse cuenta de que
 * quizás no existe.
 */

/** Una entrada de la cola de fallidos. */
export type DeadLetter = {
  dlqId: string
  timestamp: string
  failureReason: string
  errorMessage: string
  retryCount: number
  owner: string
  originalTopic: string
  /** Ausente en las entradas anteriores a que se guardara: sin esto no se puede reentregar. */
  subscriptionId?: string | null
  /** El destino que había al fallar, que no es necesariamente el vigente. */
  callbackUrl?: string | null
}

export type DeadLetterList = {
  topic: string
  returned: number
  messages: DeadLetter[]
}

export type RetryResult = {
  dlqId: string
  estado: 'entregado' | 'fallido'
  callbackUrl: string
}

/** Si este despliegue ofrece webhooks. */
export const webhooksHabilitados = config.api.dispatcher !== null

/**
 * Base de cada recurso.
 *
 * Se resuelve en cada llamada y no una vez arriba: si se guardara en una constante,
 * `webhooksHabilitados` y las URLs podrían quedar desalineados y el error saldría como un
 * `fetch('undefined/...')` en vez de como algo que se puede leer.
 */
function base(): NonNullable<typeof config.api.dispatcher> {
  const d = config.api.dispatcher
  if (!d) throw new Error('Los webhooks no están habilitados en este despliegue.')
  return d
}

export const dispatcher = {
  /** Las entregas fallidas del grupo que consulta que siguen pendientes. */
  listDeadLetters: (token: string): Promise<DeadLetterList> =>
    apiFetch<DeadLetterList>(base().deadLetters, { token }),

  /**
   * Vuelve a entregar una entrada.
   *
   * El destino lo resuelve el dispatcher a partir de la suscripción **vigente**, no de la
   * URL guardada en la entrada: esa se capturó cuando falló y desde entonces la
   * suscripción pudo cambiar de destino.
   */
  retryDeadLetter: (token: string, dlqId: string): Promise<RetryResult> =>
    apiFetch<RetryResult>(`${base().deadLetters}/${encodeURIComponent(dlqId)}/reintentar`, {
      method: 'POST',
      token,
    }),
}
