import { useContext } from 'react'
import { Alert, type MantineColor } from '@mantine/core'
import { AuthContext } from '@/contexts/auth-context'
import { SCOPE, scopeText, type ScopeKey } from '@/domain/scope'

type Props = {
  scope: ScopeKey
}

const KIND_COLOR: Record<(typeof SCOPE)[ScopeKey]['kind'], MantineColor> = {
  global:    'brand',
  namespace: 'gray',
  usuario:   'orange',
}

/**
 * El aviso de alcance de un panel.
 *
 * Es el antídoto contra la demo engañosa: el tablero mezcla datos globales, del namespace y del
 * usuario, y ninguna pantalla deja adivinar cuál es cuál. Está factorizado en un componente para
 * que agregar un panel nuevo sin su aviso se note en la revisión.
 */
export function ScopeNote({ scope }: Props) {
  const { user, namespace } = useContext(AuthContext)
  return (
    <Alert variant="outline" color={KIND_COLOR[SCOPE[scope].kind]} py={6} px="sm" mb="md" maw="90ch">
      {scopeText(scope, user, namespace)}
    </Alert>
  )
}
