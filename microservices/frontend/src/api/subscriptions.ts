import { api } from '@/config'
import { apiFetch } from './client'

const BASE = api.gateway.subscriptions

/**
 * Una suscripción webhook.
 *
 * `owner` y `createdBy` no se piden al crear: el gateway los toma del token (`namespace` y
 * `sub`), así que nadie puede registrar una suscripción a nombre de otro equipo.
 */
export type Subscription = {
  id:          string
  topic:       string
  callbackUrl: string
  owner:       string
  createdBy:   string
  createdAt:   string
  status:        'active' | 'silenced'
  silencedUntil: string | null
}

export type CreateSubscriptionPayload = { topic: string; callbackUrl: string }

export const subscriptions = {
  /** Sólo las del namespace del token — nunca las del bus entero. */
  list: (token: string, topic?: string, signal?: AbortSignal): Promise<Subscription[]> =>
    apiFetch<Subscription[]>(
      topic ? `${BASE}?topic=${encodeURIComponent(topic)}` : BASE,
      { token, signal },
    ),

  /**
   * Da de alta un webhook. `callbackUrl` pasa una validación anti-SSRF en el gateway —
   * rechaza loopback, red privada, link-local y CGNAT— y se re-valida en cada entrega, no
   * sólo al crear.
   */
  create: (token: string, payload: CreateSubscriptionPayload): Promise<Subscription> =>
    apiFetch<Subscription>(BASE, { method: 'POST', token, body: JSON.stringify(payload) }),

  remove: (token: string, id: string): Promise<void> =>
    apiFetch<void>(`${BASE}/${encodeURIComponent(id)}`, { method: 'DELETE', token }),
}
