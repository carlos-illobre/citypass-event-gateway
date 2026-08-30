import { Paper, Group, Text, Stack, Badge, type MantineColor } from '@mantine/core'
import type { ScopeKind } from '@/domain/scope'

const SCOPE_COLOR: Record<ScopeKind, MantineColor> = { global: 'grape', namespace: 'blue', usuario: 'teal' }
const SCOPE_LABEL: Record<ScopeKind, string> = { global: 'global', namespace: 'namespace', usuario: 'tuyo' }

type Props = { label: string; value: string; note: string; scope: ScopeKind; icon?: React.ReactNode }

/**
 * Una tarjeta de estadística de la vista general. Lleva su propio badge de alcance —global,
 * namespace o usuario— porque las ocho tarjetas de esa pantalla no comparten uno: mezclar
 * un contador global con uno del usuario bajo un solo título sería falso.
 */
export function StatCard({ label, value, note, scope, icon }: Props) {
  return (
    <Paper withBorder p="md" radius="md">
      <Group justify="space-between" mb={6}>
        <Text size="xs" c="dimmed" tt="uppercase" fw={600}>{label}</Text>
        <Group gap={6}>
          {icon}
          <Badge size="xs" variant="dot" color={SCOPE_COLOR[scope]}>{SCOPE_LABEL[scope]}</Badge>
        </Group>
      </Group>
      <Stack gap={2}>
        <Text fw={800} size="1.9rem" lh={1}>{value}</Text>
        <Text size="xs" c="dimmed">{note}</Text>
      </Stack>
    </Paper>
  )
}
