import { Group, Paper, Text, type MantineColor } from '@mantine/core'
import type { ScopeKind } from '@/domain/scope'

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

const SCOPE_COLOR: Record<ScopeKind, MantineColor> = {
  global:    'brand',
  namespace: 'gray',
  usuario:   'orange',
}

export function StatCard({ label, value, scope, note }: Props) {
  return (
    <Paper withBorder p="md" radius="md">
      <Group justify="space-between" align="baseline" gap="xs" wrap="nowrap">
        <Text size="sm" fw={600} c="dimmed">{label}</Text>
        <Text size="xs" fw={700} tt="uppercase" c={SCOPE_COLOR[scope]} style={{ whiteSpace: 'nowrap' }}>
          {SCOPE_LABEL[scope]}
        </Text>
      </Group>
      <Text fz={28} fw={700} lh={1.2} mt={6} mb={2} style={{ fontVariantNumeric: 'tabular-nums' }}>
        {value}
      </Text>
      <Text size="xs" c="dimmed">{note}</Text>
    </Paper>
  )
}
