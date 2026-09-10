import { useContext, useMemo } from 'react'
import { Stack, Paper, Title, Text, Code, Group, Button, Progress } from '@mantine/core'
import { IconLogout } from '@tabler/icons-react'
import { AuthContext } from '@/contexts/auth-context'
import { decodeJwt, expiresAtMs } from '@/domain/jwt'
import { useNow } from '@/hooks/useNow'
import { relativeTo } from '@/domain/time'
import { useResource } from '@/hooks/useResource'
import { gateway } from '@/api/gateway'

export function AccountView() {
  const { token, logout } = useContext(AuthContext)
  const now = useNow(1000)
  const claims = useMemo(() => decodeJwt(token), [token])
  const exp = expiresAtMs(claims)
  const quota = useResource((t, signal) => gateway.getQuota(t, signal), { intervalMs: 60_000 })

  return (
    <Stack gap="lg" maw={520}>
      <Title order={2} size="h3">Mi cuenta</Title>

      <Paper withBorder p="md">
        <Stack gap="xs">
          <Group justify="space-between"><Text c="dimmed" size="sm">Grupo (sub)</Text><Code>{claims.sub}</Code></Group>
          <Group justify="space-between"><Text c="dimmed" size="sm">Namespace</Text><Code>{claims.namespace}</Code></Group>
          <Group justify="space-between"><Text c="dimmed" size="sm">Token ID (jti)</Text><Code>{claims.jti}</Code></Group>
          <Group justify="space-between">
            <Text c="dimmed" size="sm">Vence</Text>
            <Text size="sm">{exp ? relativeTo(exp, now) : '—'}</Text>
          </Group>
        </Stack>
      </Paper>

      {quota.data && (
        <Paper withBorder p="md">
          <Text fw={600} mb="xs">Cupo</Text>
          <Text size="sm" c="dimmed" mb={6}>{quota.data.used} de {quota.data.limit} event types</Text>
          <Progress value={(quota.data.used / Math.max(1, quota.data.limit)) * 100} />
        </Paper>
      )}

      <Button color="red" variant="light" leftSection={<IconLogout size={16} />} onClick={logout}>
        Cerrar sesión
      </Button>
    </Stack>
  )
}
