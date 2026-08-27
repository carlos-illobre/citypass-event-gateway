import { Badge as MantineBadge, type MantineColor } from '@mantine/core'
import type { ReactNode } from 'react'

type Tone = 'neutral' | 'ok' | 'warning' | 'danger' | 'accent'

type Props = {
  children: ReactNode
  tone?:    Tone
  title?:   string
}

const TONE_COLOR: Record<Tone, MantineColor> = {
  neutral: 'gray',
  ok:      'teal',
  warning: 'orange',
  danger:  'red',
  accent:  'brand',
}

export function Badge({ children, tone = 'neutral', title }: Props) {
  return (
    <MantineBadge color={TONE_COLOR[tone]} variant="light" title={title} tt="none" fw={600}>
      {children}
    </MantineBadge>
  )
}
