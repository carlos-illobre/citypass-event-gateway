import { useContext } from 'react'
import { Alert } from '@mantine/core'
import { IconWorld, IconBuildingCommunity, IconUser } from '@tabler/icons-react'
import { AuthContext } from '@/contexts/auth-context'
import { SCOPE, scopeText, type ScopeKey } from '@/domain/scope'

const ICON = { global: IconWorld, namespace: IconBuildingCommunity, usuario: IconUser }
const COLOR = { global: 'grape', namespace: 'blue', usuario: 'teal' } as const

type Props = { scope: ScopeKey }

/**
 * El aviso de alcance de un panel.
 *
 * Es el antídoto contra la demo engañosa: la consola mezcla datos globales, del namespace y
 * del usuario, y ninguna pantalla deja adivinar cuál es cuál. Está factorizado en un
 * componente para que agregar un panel nuevo sin su aviso se note en la revisión — y para
 * que no haga falta un test de texto por pantalla, sino uno solo acá.
 */
export function ScopeNote({ scope }: Props) {
  const { user, namespace } = useContext(AuthContext)
  const kind = SCOPE[scope].kind
  const Icon = ICON[kind]
  return (
    <Alert variant="light" color={COLOR[kind]} icon={<Icon size={18} />} mb="md">
      {scopeText(scope, user, namespace)}
    </Alert>
  )
}
