import { api } from '@/config'
import { apiFetch } from './client'

const BASE     = api.gateway.eventTypes
const METADATA = api.gateway.eventMetadata

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
 * Sólo aparece una versión distinta de la 1 cuando alguien hizo un cambio incompatible: los
 * cambios compatibles evolucionan dentro de la misma. La v1 no lleva sufijo, así que su
 * `topic` es el FQN pelado.
 */
export type EventTypeVersion = {
  version:  number
  topic:    string
  schemaId: number | null
}

/**
 * Resumen de un event type tal como lo devuelve el listado.
 *
 * No hay un campo `status`: el gateway no archiva, borra — un tipo existe o no existe. Si
 * hace falta "desactivarlo" sin perder el historial, la operación es retirar la versión
 * vieja (`deleteEventTypeVersion`), no un estado.
 */
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

/**
 * Cuánto cupo le queda al namespace de quien pregunta, y al bus entero.
 *
 * Se cuentan nombres lógicos, no tópicos: las versiones mayores de un mismo event type son
 * el mismo contrato y no consumen cupo aparte. El total del bus se expone porque un equipo
 * puede tener lugar propio y aun así no poder crear si el techo compartido está agotado —
 * sin este dato ese rechazo se leería como un problema del equipo.
 */
export type EventTypeQuota = {
  namespace:      string
  used:           number
  limit:          number
  remaining:      number
  totalUsed:      number
  totalLimit:     number
  totalRemaining: number
}

/**
 * Un event type dentro de un archivo de backup.
 *
 * `fields` viene en el mismo formato que espera el alta, así que restaurar es volver a
 * registrar sin traducción. `version` y `versions` describen lo que había al exportar: son
 * informativos, porque una restauración deja todo en v1.
 */
export type BackupEventType = {
  name:     string
  fqn:      string
  fields:   AvroField[]
  version:  number
  topic:    string
  versions: EventTypeVersion[]
}

export type SchemaBackup = {
  formatVersion: number
  namespace:     string
  exportedAt:    string
  eventTypes:    BackupEventType[]
}

/** El formato que esta versión de la interfaz sabe restaurar. */
export const BACKUP_FORMAT_VERSION = 1

export type CreateEventTypePayload = { name: string; fields: AvroField[] }

export type CreateEventTypeResponse = {
  fqn: string; namespace: string; name: string; topic: string; version: number; schemaId: number
}

/**
 * Qué pasó al cambiar el schema de un event type.
 *
 * Lo decide el Schema Registry, no quien llama: si el cambio es compatible se registra en
 * el mismo tópico y ningún consumidor se entera; si no lo es, estrena una versión mayor con
 * tópico propio y la anterior queda sirviendo su historial. `subscriptionsOnPreviousVersion`
 * es el dato que convierte una ruptura de contrato en una decisión consciente.
 */
export type SchemaChangeResult = {
  fqn:      string
  topic:    string
  version:  number
  schemaId: number
  breaking:  boolean
  unchanged: boolean
  previousTopic: string | null
  subscriptionsOnPreviousVersion: number | null
}

/** Quién quedaría sin recibir un tipo si se borra (extensión del 409). */
export type Subscriber = { owner: string; topic: string }

export type DeleteResult = { fqn: string; deletedTopics: string[]; subscriptionsRemoved: number }

/**
 * Los nueve campos que el gateway calcula e inyecta en todo evento.
 *
 * `receivedAt` es epoch en **milisegundos** (número), a diferencia de casi cualquier otra
 * fecha de la API, que viaja en ISO-8601 (string). Es una inconsistencia real del backend,
 * no un error de tipeo acá — `domain/time.ts` la absorbe.
 */
export type EventMetadata = {
  eventId:        string
  eventType:      string
  receivedAt:     number
  source:         string
  tokenId:        string
  schemaId:       number
  payloadHash:    string
  gatewayVersion: string
  instanceId:     string
}

export type PublishEventResponse = { metadata: EventMetadata; data: Record<string, unknown> }

/**
 * Respuesta de "mis últimos eventos".
 *
 * `topicsScanned` distingue "todavía no publicaste nada" de "tu namespace no tiene event
 * types registrados", que desde la lista vacía se ven igual.
 */
export type RecentEventsResponse = { returned: number; topicsScanned: number; events: PublishEventResponse[] }

export const gateway = {
  /** @param namespace Si se pasa, acota el listado a ese namespace. El listado es global. */
  listEventTypes: (token: string, namespace?: string, signal?: AbortSignal): Promise<EventTypeSummary[]> =>
    apiFetch<EventTypeSummary[]>(
      namespace ? `${BASE}?namespace=${encodeURIComponent(namespace)}` : BASE,
      { token, signal },
    ),

  /**
   * Últimos eventos publicados por el usuario del token — no el namespace, no el bus.
   * No es el historial completo: el gateway lee la cola de los tópicos del namespace y
   * filtra en memoria por `metadata.source == sub`, porque Kafka no permite buscar por campo.
   */
  listMyEvents: (token: string, limit = 50, signal?: AbortSignal): Promise<RecentEventsResponse> =>
    apiFetch<RecentEventsResponse>(`${api.gateway.events}?limit=${limit}`, { token, signal }),

  getQuota: (token: string, signal?: AbortSignal): Promise<EventTypeQuota> =>
    apiFetch<EventTypeQuota>(`${BASE}/quota`, { token, signal }),

  /**
   * Backup de todos los event types del namespace del token, en una sola llamada.
   * Un listado más un `GET` por tipo daría una foto que puede cambiar entre medio.
   */
  exportBackup: (token: string, signal?: AbortSignal): Promise<SchemaBackup> =>
    apiFetch<SchemaBackup>(`${BASE}/export`, { token, signal }),

  getEventTypeSchema: (token: string, fqn: string, signal?: AbortSignal): Promise<EventTypeSchema> =>
    apiFetch<EventTypeSchema>(path(fqn), { token, signal }),

  /** El schema del record `metadata`. Se pide al gateway y no se copia: es su fuente de verdad. */
  getMetadataSchema: (token: string, signal?: AbortSignal): Promise<EventTypeSchema> =>
    apiFetch<EventTypeSchema>(METADATA, { token, signal }),

  /** Cambia los campos de negocio. Se manda la lista COMPLETA, no un parche; el FQN no cambia. */
  updateEventType: (token: string, fqn: string, fields: AvroField[]): Promise<SchemaChangeResult> =>
    apiFetch<SchemaChangeResult>(path(fqn), { method: 'PUT', token, body: JSON.stringify({ fields }) }),

  /** Borra todas las versiones. 409 con `subscribers` si hay equipos ajenos suscriptos. */
  deleteEventType: (token: string, fqn: string): Promise<DeleteResult> =>
    apiFetch<DeleteResult>(path(fqn), { method: 'DELETE', token }),

  /** Retira una versión mayor vieja. La vigente no se puede borrar sola. */
  deleteEventTypeVersion: (token: string, fqn: string, version: number): Promise<DeleteResult> =>
    apiFetch<DeleteResult>(`${path(fqn)}/versions/${version}`, { method: 'DELETE', token }),

  createEventType: (token: string, payload: CreateEventTypePayload): Promise<CreateEventTypeResponse> =>
    apiFetch<CreateEventTypeResponse>(BASE, { method: 'POST', token, body: JSON.stringify(payload) }),

  /** El body es directamente el payload de negocio: el tipo va en la ruta, no hay envelope que armar. */
  publishEvent: (token: string, fqn: string, data: Record<string, unknown>): Promise<PublishEventResponse> =>
    apiFetch<PublishEventResponse>(`${path(fqn)}/events`, { method: 'POST', token, body: JSON.stringify(data) }),

  health: (signal?: AbortSignal) => apiFetch<{ status: string; service: string }>(api.gateway.health, { signal }),
}
