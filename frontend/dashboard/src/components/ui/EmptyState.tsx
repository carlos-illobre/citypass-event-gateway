import { Stack, Text } from '@mantine/core'
import type { ReactNode } from 'react'

type Props = {
  title:    string
  /** Por qué está vacío y qué hacer al respecto. Un «sin datos» pelado no ayuda a nadie. */
  detail?:  ReactNode
}

export function EmptyState({ title, detail }: Props) {
  return (
    <Stack align="center" gap="xs" py="xl" ta="center">
      <Text fw={600}>{title}</Text>
      {detail && <Text size="sm" c="dimmed" maw={480}>{detail}</Text>}
    </Stack>
  )
}
