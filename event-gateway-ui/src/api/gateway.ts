import { config } from '@/config'
import { apiFetch } from './client'

const BASE     = config.api.gateway.eventTypes
const METADATA = config.api.gateway.eventMetadata

const path = (fqn: string) => `${BASE}/${encodeURIComponent(fqn)}`

export type AvroField = { name: string; type: unknown }

export type EventTypeSchema = {
  type:      string
  name:      string
  namespace: string
  fields:    AvroField[]
}

/** Un event type archivado conserva schema e historial, pero no admite nuevos eventos. */
export type EventTypeStatus = 'active' | 'archived'

/** Resumen de un event type tal como lo devuelve el listado. */
export type EventTypeSummary = {
  fqn:        string
  namespace:  string
  name:       string
  schemaId:   number | null
  status:     EventTypeStatus
  archivedAt: string | null
}

export type CreateEventTypePayload = {
  name: string
  fields: AvroField[]
}

export type CreateEventTypeResponse = {
  fqn:       string
  namespace: string
  name:      string
  schemaId:  number
}

/**
 * Lo que devuelve publicar: el envelope exacto que quedó en el tópico.
 *
 * `metadata` la calcula el gateway a partir del token y del payload, así que el productor
 * no puede reconstruirla por su cuenta — de ahí que la respuesta la devuelva entera.
 */
export type EventMetadata = {
  eventId:        string
  eventType:      string
  /** Epoch en milisegundos, como viaja en Avro. */
  receivedAt:     number
  source:         string
  tokenId:        string
  schemaId:       number
  payloadHash:    string
  gatewayVersion: string
  instanceId:     string
}

export type PublishEventResponse = {
  metadata: EventMetadata
  data:     Record<string, unknown>
}

/**
 * Respuesta de «mis últimos eventos».
 *
 * `topicsScanned` distingue «todavía no publicaste nada» de «tu namespace no tiene event
 * types registrados», que desde la lista vacía se ven igual.
 */
export type RecentEventsResponse = {
  returned:      number
  topicsScanned: number
  events:        PublishEventResponse[]
}

export const gateway = {
  /** @param namespace Si se pasa, acota el listado a ese namespace. */
  listEventTypes: (token: string, namespace?: string): Promise<EventTypeSummary[]> =>
    apiFetch<EventTypeSummary[]>(
      namespace ? `${BASE}?namespace=${encodeURIComponent(namespace)}` : BASE,
      { token }
    ),

  /**
   * Últimos eventos publicados por el usuario del token.
   *
   * No es el historial completo: el gateway lee la cola de los tópicos del namespace y
   * filtra en memoria, porque Kafka no permite buscar por campo.
   */
  listMyEvents: (token: string, limit = 50): Promise<RecentEventsResponse> =>
    apiFetch<RecentEventsResponse>(`${config.api.gateway.events}?limit=${limit}`, { token }),

  getEventTypeSchema: (token: string, fqn: string): Promise<EventTypeSchema> =>
    apiFetch<EventTypeSchema>(path(fqn), { token }),

  /**
   * Schema del record `metadata` que el gateway inyecta en todo evento.
   *
   * Se consulta al gateway en vez de mantener una copia local: la metadata es su
   * fuente de verdad y una copia acá quedaría desincronizada al evolucionar.
   */
  getMetadataSchema: (token: string): Promise<EventTypeSchema> =>
    apiFetch<EventTypeSchema>(METADATA, { token }),

  /**
   * Archiva un event type: baja lógica, no borrado.
   *
   * Es un PATCH y no un DELETE porque el recurso no desaparece — cambia un atributo
   * de su estado. La operación inversa sería el mismo llamado con otro `status`.
   */
  archiveEventType: (token: string, fqn: string): Promise<{ fqn: string; status: EventTypeStatus }> =>
    apiFetch(path(fqn), {
      method: 'PATCH',
      token,
      body: JSON.stringify({ status: 'archived' }),
    }),

  createEventType: (token: string, payload: CreateEventTypePayload): Promise<CreateEventTypeResponse> =>
    apiFetch<CreateEventTypeResponse>(BASE, {
      method: 'POST',
      token,
      body: JSON.stringify(payload),
    }),

  /** El body es directamente el payload de negocio: el tipo va en la ruta. */
  publishEvent: (token: string, fqn: string, data: Record<string, unknown>): Promise<PublishEventResponse> =>
    apiFetch<PublishEventResponse>(`${path(fqn)}/events`, {
      method: 'POST',
      token,
      body: JSON.stringify(data),
    }),
}
