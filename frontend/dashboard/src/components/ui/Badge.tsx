import type { ReactNode } from 'react'
import './Badge.css'

type Tone = 'neutral' | 'ok' | 'warning' | 'danger' | 'accent'

type Props = {
  children: ReactNode
  tone?:    Tone
  title?:   string
}

export function Badge({ children, tone = 'neutral', title }: Props) {
  return <span className={`badge badge--${tone}`} title={title}>{children}</span>
}
