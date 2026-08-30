import { Alert, List, Text } from '@mantine/core'
import { IconAlertTriangle } from '@tabler/icons-react'
import { isApiError, type Problem } from '@/api/client'

type Props = {
  /** El mensaje ya resuelto (`detail ?? title ?? ...`), o el error crudo si se prefiere pasarlo así. */
  message: string
  /** El cuerpo RFC 9457 completo, para leer sus miembros de extensión. Si no se pasa, se intenta sacar de `error`. */
  problem?: Problem
  error?: unknown
}

/**
 * Un error de la API, con sus extensiones a la vista.
 *
 * `detail`/`title` ya vienen resueltos en `message`, pero dos respuestas del gateway traen
 * datos accionables que se perderían si sólo se mostrara el texto: el 404 al publicar dice
 * qué tipos SÍ existen, y el 409 al borrar dice QUIÉN está suscripto. Mostrarlos es la
 * diferencia entre "no se pudo" y "esto es lo que tenés que hacer para que se pueda".
 */
export function ProblemAlert({ message, problem, error }: Props) {
  const body = problem ?? (isApiError(error) ? error.problem : undefined)

  return (
    <Alert variant="light" color="red" icon={<IconAlertTriangle size={18} />} title={body?.title}>
      <Text size="sm">{message}</Text>

      {body?.availableEventTypes && body.availableEventTypes.length > 0 && (
        <>
          <Text size="sm" mt="xs" fw={500}>Tipos disponibles:</Text>
          <List size="sm">
            {body.availableEventTypes.map(fqn => <List.Item key={fqn}>{fqn}</List.Item>)}
          </List>
        </>
      )}

      {body?.subscribers && body.subscribers.length > 0 && (
        <>
          <Text size="sm" mt="xs" fw={500}>Equipos suscriptos — coordiná la baja con ellos:</Text>
          <List size="sm">
            {body.subscribers.map(s => (
              <List.Item key={`${s.owner}-${s.topic}`}>{s.owner} · {s.topic}</List.Item>
            ))}
          </List>
        </>
      )}
    </Alert>
  )
}
