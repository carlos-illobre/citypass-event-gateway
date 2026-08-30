import { BACKUP_FORMAT_VERSION, type AvroField, type SchemaBackup } from '@/api/gateway'

/** Cómo terminó cada event type de un backup que se está restaurando. */
export type RestoreOutcome = 'created' | 'skipped' | 'failed'

export type RestoreEntry = {
  name:    string
  outcome: RestoreOutcome
  detail:  string
}

/**
 * Valida un archivo de backup antes de tocar nada.
 *
 * La validación es previa y completa a propósito: restaurar crea event types de a uno, y
 * descubrir a mitad de camino que el archivo no servía dejaría media restauración hecha
 * sin forma cómoda de deshacerla.
 *
 * @returns El backup si es válido, o el motivo del rechazo.
 */
export function parseBackup(raw: string): { backup: SchemaBackup } | { error: string } {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return { error: 'El archivo no es JSON válido.' }
  }

  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return { error: 'El archivo no tiene la forma de un backup: se esperaba un objeto JSON.' }
  }

  const candidate = parsed as Partial<SchemaBackup>

  if (candidate.formatVersion !== BACKUP_FORMAT_VERSION) {
    return {
      error: `Formato de backup no soportado (formatVersion ${String(candidate.formatVersion)}). ` +
             `Esta versión entiende el ${BACKUP_FORMAT_VERSION}.`,
    }
  }

  if (!Array.isArray(candidate.eventTypes)) {
    return { error: 'El backup no trae la lista `eventTypes`.' }
  }

  const invalido = candidate.eventTypes.findIndex(
    tipo => typeof tipo?.name !== 'string' || !Array.isArray(tipo?.fields)
  )
  if (invalido >= 0) {
    return { error: `El event type en la posición ${invalido + 1} no tiene \`name\` y \`fields\`.` }
  }

  return { backup: candidate as SchemaBackup }
}

/**
 * Los event types del backup que todavía no existen, en el orden del archivo.
 *
 * Se comparan **nombres** y no FQNs porque el namespace lo pone el gateway a partir del
 * token: un backup de otro equipo se restaura dentro del namespace propio, y el FQN del
 * archivo no dice nada sobre si acá ya existe algo con ese nombre.
 */
export function pendientes(backup: SchemaBackup, existentes: string[]): string[] {
  const yaEstan = new Set(existentes)
  return backup.eventTypes.map(tipo => tipo.name).filter(name => !yaEstan.has(name))
}

/** Los campos de un event type del backup, listos para el alta. */
export function camposDe(backup: SchemaBackup, name: string): AvroField[] {
  return backup.eventTypes.find(tipo => tipo.name === name)?.fields ?? []
}

/** Resumen legible de una restauración terminada. */
export function resumen(entradas: RestoreEntry[]): string {
  const cuenta = (outcome: RestoreOutcome) => entradas.filter(e => e.outcome === outcome).length
  const partes = [
    `${cuenta('created')} creado(s)`,
    `${cuenta('skipped')} omitido(s)`,
    `${cuenta('failed')} con error`,
  ]
  return partes.join(' · ')
}
