import { useContext } from 'react'
import { SimpleGrid, Stack, Title, Group, Progress, Text, Paper } from '@mantine/core'
import { AuthContext } from '@/contexts/auth-context'
import { useResource } from '@/hooks/useResource'
import { usePolling } from '@/hooks/usePolling'
import { gateway } from '@/api/gateway'
import { subscriptions as subscriptionsApi } from '@/api/subscriptions'
import { deadLetters as deadLettersApi } from '@/api/deadLetters'
import { anomalies as anomaliesApi } from '@/api/anomalies'
import { summarizeCatalog } from '@/domain/eventTypes'
import { buildKpis } from '@/domain/overview'
import { POLL_MS, LIMITS } from '@/config'
import { StatCard } from '@/components/ui/StatCard'

/**
 * La vista general. Combina cinco fuentes con tres alcances distintos —el catálogo y el
 * detector son globales, la cola de fallidos y las suscripciones son del namespace, los
 * eventos son sólo del usuario— y `buildKpis` es lo que arma cada tarjeta con el alcance
 * que le corresponde, en vez de un encabezado único que mentiría.
 */
export function OverviewView() {
  const { user, namespace } = useContext(AuthContext)

  const catalog = useResource(
    (t, signal) => gateway.listEventTypes(t, undefined, signal), { intervalMs: POLL_MS.catalog },
  )
  const events = useResource(
    (t, signal) => gateway.listMyEvents(t, LIMITS.events, signal), { intervalMs: POLL_MS.events },
  )
  const dead = useResource(
    (t, signal) => deadLettersApi.list(t, LIMITS.deadLetters, signal), { intervalMs: POLL_MS.deadLetters },
  )
  const subs = useResource(
    (t, signal) => subscriptionsApi.list(t, undefined, signal), { intervalMs: POLL_MS.subscriptions },
  )
  const quota = useResource((t, signal) => gateway.getQuota(t, signal), { intervalMs: POLL_MS.catalog })
  // El detector no pide token: usa el sondeo directo, no `useResource`, para que se note en
  // el código que este recurso es distinto — no es un descuido de autenticación.
  const model = usePolling(signal => anomaliesApi.modelStatus(signal), { intervalMs: POLL_MS.modelStatus })

  const summary = catalog.data ? summarizeCatalog(catalog.data, namespace) : null

  const kpis = buildKpis({
    catalog: summary,
    model: model.data,
    myEvents: events.data?.events.length ?? null,
    deadLetters: dead.data?.messages.length ?? null,
    subscriptions: subs.data?.length ?? null,
    namespace, user,
  })

  return (
    <Stack gap="xl">
      <Title order={2} size="h3">Vista general</Title>

      <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }} spacing="md">
        {kpis.map(k => <StatCard key={k.id} label={k.label} value={k.value} note={k.note} scope={k.scope} />)}
      </SimpleGrid>

      {quota.data && (
        <Paper withBorder p="md">
          <Text fw={600} mb="sm">Cupo de event types</Text>
          <Stack gap="md">
            <div>
              <Group justify="space-between" mb={4}>
                <Text size="sm">Tu namespace ({namespace || '—'})</Text>
                <Text size="sm" c="dimmed">{quota.data.used} / {quota.data.limit}</Text>
              </Group>
              <Progress value={(quota.data.used / Math.max(1, quota.data.limit)) * 100}
                color={quota.data.remaining === 0 ? 'red' : quota.data.remaining <= quota.data.limit * 0.2 ? 'orange' : 'citypass'} />
            </div>
            <div>
              <Group justify="space-between" mb={4}>
                <Text size="sm">Bus entero (los 8 grupos)</Text>
                <Text size="sm" c="dimmed">{quota.data.totalUsed} / {quota.data.totalLimit}</Text>
              </Group>
              <Progress value={(quota.data.totalUsed / Math.max(1, quota.data.totalLimit)) * 100} color="grape" />
            </div>
          </Stack>
        </Paper>
      )}
    </Stack>
  )
}
