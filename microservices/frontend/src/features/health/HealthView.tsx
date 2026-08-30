import { Stack, SimpleGrid, Paper, Group, Badge, Text, Anchor, Title } from '@mantine/core'
import { IconExternalLink } from '@tabler/icons-react'
import { usePolling } from '@/hooks/usePolling'
import { gateway } from '@/api/gateway'
import { auth } from '@/api/auth'
import { anomalies } from '@/api/anomalies'
import { OPS_LINKS, POLL_MS } from '@/config'

function ServicePill({ label, poll }: { label: string; poll: { data: { status: string } | null; error: string; loading: boolean } }) {
  const up = poll.data?.status === 'UP'
  return (
    <Paper withBorder p="md">
      <Group justify="space-between">
        <Text fw={600}>{label}</Text>
        <Badge color={poll.loading ? 'gray' : up ? 'teal' : 'red'} variant="light">
          {poll.loading ? 'consultando…' : up ? 'UP' : poll.error ? 'sin respuesta' : 'DOWN'}
        </Badge>
      </Group>
    </Paper>
  )
}

/**
 * Salud del bus. Sólo `GET /health` de cada servicio — las métricas de Prometheus viven en
 * el puerto 9090, aislado a propósito (no se publica salvo en loopback, y nunca lo rutea
 * el reverse-proxy en producción: `docs/SECURITY.md`). Para eso están los enlaces a Grafana.
 */
export function HealthView() {
  const gw  = usePolling(signal => gateway.health(signal), { intervalMs: POLL_MS.health })
  const au  = usePolling(signal => auth.health(signal), { intervalMs: POLL_MS.health })
  const an  = usePolling(signal => anomalies.health(signal), { intervalMs: POLL_MS.health })

  return (
    <Stack gap="lg">
      <Title order={2} size="h3">Salud del bus</Title>
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <ServicePill label="Event Gateway" poll={gw} />
        <ServicePill label="Simulador de identidad" poll={au} />
        <ServicePill label="Detector de anomalías" poll={an} />
      </SimpleGrid>

      <Paper withBorder p="md">
        <Text fw={600} mb="sm">Consolas de operación</Text>
        <Stack gap={6}>
          {OPS_LINKS.map(link => (
            <Group key={link.label} justify="space-between">
              <div>
                <Anchor href={link.href} target="_blank" rel="noreferrer">
                  <Group gap={4}>{link.label} <IconExternalLink size={13} /></Group>
                </Anchor>
                <Text size="xs" c="dimmed">{link.note}</Text>
              </div>
            </Group>
          ))}
        </Stack>
      </Paper>
    </Stack>
  )
}
