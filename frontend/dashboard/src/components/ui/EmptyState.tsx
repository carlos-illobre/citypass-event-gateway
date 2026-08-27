import type { ReactNode } from 'react'
import './EmptyState.css'

type Props = {
  title:    string
  /** Por qué está vacío y qué hacer al respecto. Un «sin datos» pelado no ayuda a nadie. */
  detail?:  ReactNode
}

export function EmptyState({ title, detail }: Props) {
  return (
    <div className="empty-state">
      <p className="empty-state__title">{title}</p>
      {detail && <div className="empty-state__detail">{detail}</div>}
    </div>
  )
}
