import { useContext, useMemo } from 'react'
import { Box, Card, Group, SimpleGrid, Stack, Text, Title } from '@mantine/core'
import { anomaly } from '@/api/anomalies'
import { gateway } from '@/api/gateway'
import { POLL_MS, config } from '@/config'
import { AuthContext } from '@/contexts/auth-context'
import { usePolling } from '@/hooks/usePolling'
import { useResource } from '@/hooks/useResource'
import { summarizeCatalog } from '@/domain/eventTypes'
import { buildKpis } from '@/domain/overview'
import { StatCard } from '@/components/ui/StatCard'
import { ErrorBanner } from '@/components/ui/ErrorBanner'
import { RefreshBar } from '@/components/ui/RefreshBar'
import { BarList } from '@/components/charts/BarList'
import { HealthPill } from './HealthPill'

/**
 * La primera pantalla: el estado del bus en diez segundos.
 *
 * Cada recurso tiene su propio sondeo en vez de uno solo que los junte: si el catálogo tarda o
 * falla, las anomalías se siguen actualizando igual. Y cada tarjeta declara su alcance, porque
 * las fuentes de esta pantalla tienen tres distintos y un encabezado único mentiría.
 */
export function OverviewView() {
  const { user, namespace } = useContext(AuthContext)

  const catalogPoll = useResource((t, s) => gateway.listEventTypes(t, undefined, s), { intervalMs: POLL_MS.catalog })
  const eventsPoll  = useResource((t, s) => gateway.listMyEvents(t, config.limits.events, s), { intervalMs: POLL_MS.events })
  const dlqPoll     = useResource((t, s) => gateway.listDeadLetters(t, config.limits.deadLetters, s), { intervalMs: POLL_MS.deadLetters })
  const subsPoll    = useResource((t, s) => gateway.listSubscriptions(t, s), { intervalMs: POLL_MS.subscriptions })
  const modelPoll   = usePolling(s => anomaly.modelStatus(s), { intervalMs: POLL_MS.modelStatus })
  const gwHealth    = usePolling(s => gateway.health(s), { intervalMs: POLL_MS.health })
  const anHealth    = usePolling(s => anomaly.health(s), { intervalMs: POLL_MS.health })

  const catalog = useMemo(
    () => (catalogPoll.data ? summarizeCatalog(catalogPoll.data, namespace) : null),
    [catalogPoll.data, namespace]
  )

  const kpis = useMemo(() => buildKpis({
    catalog,
    model:         modelPoll.data,
    myEvents:      eventsPoll.data?.returned ?? null,
    deadLetters:   dlqPoll.data?.returned ?? null,
    subscriptions: subsPoll.data?.length ?? null,
    namespace,
    user,
  }), [catalog, modelPoll.data, eventsPoll.data, dlqPoll.data, subsPoll.data, namespace, user])

  // Un solo error a la vez, el primero que haya: apilar cinco banners por un gateway caído
  // ocuparía media pantalla para decir lo mismo cinco veces.
  const error = catalogPoll.error || modelPoll.error || eventsPoll.error || dlqPoll.error

  return (
    <Stack gap="md">
      <Group justify="space-between" wrap="wrap">
        <div>
          <Title order={2} fz="lg" lh={1.2}>Vista general</Title>
          <Text size="sm" c="dimmed">
            Cada tarjeta indica a qué alcanza su número: el bus entero, tu namespace o sólo vos.
          </Text>
        </div>
        <RefreshBar
          lastUpdated={catalogPoll.lastUpdated}
          refreshing={catalogPoll.refreshing}
          paused={catalogPoll.paused}
          onRefresh={() => {
            catalogPoll.refresh(); eventsPoll.refresh(); dlqPoll.refresh()
            subsPoll.refresh(); modelPoll.refresh()
          }}
        />
      </Group>

      {error && <ErrorBanner message={error} />}

      <Group gap="xs">
        <HealthPill name="event-gateway" poll={gwHealth} />
        <HealthPill name="anomaly-detector" poll={anHealth} />
      </Group>

      <SimpleGrid cols={{ base: 1, xs: 2, md: 3, lg: 5 }} spacing="md">
        {kpis.map(kpi => (
          <StatCard key={kpi.id} label={kpi.label} value={kpi.value} scope={kpi.scope} note={kpi.note} />
        ))}
      </SimpleGrid>

      {catalog && catalog.byNamespace.length > 0 && (
        <Card withBorder radius="md" padding={0}>
          <Card.Section withBorder inheritPadding py="xs" px="md">
            <Group justify="space-between">
              <Text fw={700} fz="sm">Tipos de evento por grupo</Text>
              <Text size="sm" c="dimmed">todos los namespaces del bus</Text>
            </Group>
          </Card.Section>
          <Box p="md">
            <BarList data={catalog.byNamespace} limit={10} label="Tipos de evento por namespace" />
          </Box>
        </Card>
      )}
    </Stack>
  )
}
