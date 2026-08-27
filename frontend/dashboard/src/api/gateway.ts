import { config } from '@/config'
import { apiFetch } from './client'

const path = (base: string, fqn: string) => `${base}/${encodeURIComponent(fqn)}`

/** Un event type archivado conserva schema e historial, pero no admite nuevos eventos. */
export type EventTypeStatus = 'active' | 'archived'

/** Resumen de un event type tal como lo devuelve el listado. */
export type EventTypeSummary = {
  fqn:        string
  namespace:  string
  name:       string
  /** `null` mientras no esté registrado en el Schema Registry. */
  schemaId:   number | null
  status:     EventTypeStatus
  archivedAt: string | null
}

/**
 * El sobre con el que el gateway envuelve todo evento. Nueve campos, todos obligatorios.
 *
 * `source` es el `sub` del token y lo estampa el gateway, así que no se puede falsificar: es
 * también lo que hace que `GET /events` devuelva sólo lo de uno.
 */
export type EventMetadata = {
  eventId:        string
  eventType:      string
  /** Epoch en milisegundos. El detector de anomalías, en cambio, usa ISO-8601. */
  receivedAt:     number
  source:         string
  tokenId:        string
  schemaId:       number
  payloadHash:    string
  gatewayVersion: string
  instanceId:     string
}

export type BusEvent = {
  metadata: EventMetadata
  data:     Record<string, unknown>
}

export type RecentEventsResponse = {
  returned:      number
  /** Cuántos tópicos se recorrieron. Distingue «no publiqué» de «no tengo tipos». */
  topicsScanned: number
  events:        BusEvent[]
}

export type DeadLetterReason = 'DESERIALIZATION_ERROR' | 'WEBHOOK_DELIVERY_FAILED'

export type DeadLetter = {
  dlqId:                 string
  /** ISO-8601. */
  timestamp:             string
  failureReason:         DeadLetterReason | string
  errorMessage:          string
  retryCount:            number
  owner:                 string
  originalTopic:         string
  originalKey:           string | null
  originalPayloadBase64: string
}

export type DeadLettersResponse = {
  topic:    string
  returned: number
  messages: DeadLetter[]
}

export type Subscription = {
  id:          string
  topic:       string
  callbackUrl: string
  owner:       string
  createdBy:   string
  createdAt:   string
}

export type ServiceHealth = {
  status?:  string
  service?: string
}

export const gateway = {
  /**
   * El listado NO está acotado al namespace de quien pregunta: pide token, pero devuelve los
   * tipos de todos los grupos. Es la única vista global del bus disponible por HTTP.
   *
   * @param namespace Si se pasa, acota el listado a ese namespace.
   */
  listEventTypes: (token: string, namespace?: string, signal?: AbortSignal) =>
    apiFetch<EventTypeSummary[]>(
      namespace
        ? `${config.api.gateway.eventTypes}?namespace=${encodeURIComponent(namespace)}`
        : config.api.gateway.eventTypes,
      { token, signal }
    ),

  /** El esquema Avro completo del tipo, tal como está registrado. */
  getEventTypeSchema: (token: string, fqn: string, signal?: AbortSignal) =>
    apiFetch<Record<string, unknown>>(path(config.api.gateway.eventTypes, fqn), { token, signal }),

  /** El esquema del sobre. Es el mismo para todos los eventos del bus. */
  getMetadataSchema: (token: string, signal?: AbortSignal) =>
    apiFetch<Record<string, unknown>>(config.api.gateway.eventMetadata, { token, signal }),

  /**
   * Los últimos eventos publicados por el usuario del token — no por su namespace.
   *
   * El filtro `metadata.source == sub` está del lado del gateway y no tiene parámetro para
   * ampliarlo. Cualquier pantalla que muestre esto tiene que decirlo.
   */
  listMyEvents: (token: string, limit: number = config.limits.events, signal?: AbortSignal) =>
    apiFetch<RecentEventsResponse>(`${config.api.gateway.events}?limit=${limit}`, { token, signal }),

  /** La cola de fallidos del namespace propio. */
  listDeadLetters: (token: string, limit: number = config.limits.deadLetters, signal?: AbortSignal) =>
    apiFetch<DeadLettersResponse>(
      `${config.api.gateway.deadLetters}?limit=${limit}`,
      { token, signal }
    ),

  listSubscriptions: (token: string, signal?: AbortSignal) =>
    apiFetch<Subscription[]>(config.api.gateway.subscriptions, { token, signal }),

  /** Sin token: es el único endpoint público del gateway. */
  health: (signal?: AbortSignal) =>
    apiFetch<ServiceHealth>(config.api.gateway.health, { signal }),
}
