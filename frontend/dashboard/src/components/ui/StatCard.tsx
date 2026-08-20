import type { ScopeKind } from '@/domain/scope'
import './StatCard.css'

type Props = {
  label: string
  value: string
  /**
   * El alcance de ESTE número. Va en la tarjeta y no en el encabezado de la pantalla porque las
   * tarjetas de la vista general no comparten alcance: mezclarlas bajo un solo título sería
   * cierto en los números y falso en lo que comunica.
   */
  scope: ScopeKind
  note:  string
}

const SCOPE_LABEL: Record<ScopeKind, string> = {
  global:    'todos los grupos',
  namespace: 'tu namespace',
  usuario:   'sólo vos',
}

export function StatCard({ label, value, scope, note }: Props) {
  return (
    <div className="stat-card card">
      <div className="stat-card__head">
        <span className="stat-card__label">{label}</span>
        <span className={`stat-card__scope stat-card__scope--${scope}`}>{SCOPE_LABEL[scope]}</span>
      </div>
      <p className="stat-card__value">{value}</p>
      <p className="stat-card__note">{note}</p>
    </div>
  )
}
