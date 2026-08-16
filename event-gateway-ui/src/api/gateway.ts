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

/**
 * Una versión mayor de un event type, con su tópico propio.
 *
 * Sólo aparece una versión distinta de la 1 cuando alguien hizo un cambio incompatible:
 * los cambios compatibles evolucionan dentro de la misma. La v1 no lleva sufijo, así que
 * su `topic` es el FQN pelado.
 */
export type EventTypeVersion = {
  version:  number
  topic:    string
  schemaId: number | null
}

/** Resumen de un event type tal como lo devuelve el listado. */
export type EventTypeSummary = {
  fqn:       string
  namespace: string
  name:      string
  /** Dónde caen los eventos nuevos: el tópico de la versión vigente. */
  topic:     string
  version:   number
  schemaId:  number | null
  versions:  EventTypeVersion[]
}

export type CreateEventTypePayload = {
  name: string
  fields: AvroField[]
}

export type CreateEventTypeResponse = {
  fqn:       string
  namespace: string
  name:      string
  topic:     string
  version:   number
  schemaId:  number
}

/**
 * Qué pasó al cambiar el schema de un event type.
 *
 * Lo decide el Schema Registry, no quien llama: si el cambio es compatible se registra en
 * el mismo tópico y ningún consumidor se entera; si no lo es, estrena una versión mayor
 * con tópico propio y la anterior queda sirviendo su historial.
 */
export type SchemaChangeResult = {
  fqn:      string
  topic:    string
  version:  number
  schemaId: number
  /** El cambio rompió el contrato y por eso estrenó versión. */
  breaking: boolean
  /** El schema enviado era idéntico al vigente: no se hizo nada. */
  unchanged: boolean
  previousTopic: string | null
  /** Cuántos webhooks quedaron en la versión anterior, o null si no hubo ruptura. */
  subscriptionsOnPreviousVersion: number | null
}

/** Resultado de borrar un event type o una de sus versiones. */
export type DeleteResult = {
  fqn:                  string
  deletedTopics:        string[]
  subscriptionsRemoved: number
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
   * Cambia los campos de negocio de un event type existente.
   *
   * Se manda la lista **completa** de campos, no un parche. El FQN no puede cambiar:
   * viaja en la ruta y es lo que identifica al event type.
   */
  updateEventType: (token: string, fqn: string, fields: AvroField[]): Promise<SchemaChangeResult> =>
    apiFetch<SchemaChangeResult>(path(fqn), {
      method: 'PUT',
      token,
      body: JSON.stringify({ fields }),
    }),

  /**
   * Borra un event type entero: todas sus versiones, sus tópicos y sus schemas.
   *
   * Es permanente y libera el nombre. Se rechaza con 409 si hay equipos ajenos
   * suscriptos, y el problem detail trae quiénes son.
   */
  deleteEventType: (token: string, fqn: string): Promise<DeleteResult> =>
    apiFetch<DeleteResult>(path(fqn), { method: 'DELETE', token }),

  /** Retira una versión mayor vieja. La vigente no se puede borrar sola. */
  deleteEventTypeVersion: (token: string, fqn: string, version: number): Promise<DeleteResult> =>
    apiFetch<DeleteResult>(`${path(fqn)}/versions/${version}`, { method: 'DELETE', token }),

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
