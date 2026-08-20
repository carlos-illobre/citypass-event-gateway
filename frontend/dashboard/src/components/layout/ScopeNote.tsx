import { useContext } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { SCOPE, scopeText, type ScopeKey } from '@/domain/scope'
import './ScopeNote.css'

type Props = {
  scope: ScopeKey
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
    <p className={`scope-note scope-note--${SCOPE[scope].kind}`}>
      {scopeText(scope, user, namespace)}
    </p>
  )
}
