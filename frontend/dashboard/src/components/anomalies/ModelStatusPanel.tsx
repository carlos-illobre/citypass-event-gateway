import { Card, Group, Progress, SimpleGrid, Stack, Text } from '@mantine/core'
import type { ModelStatus } from '@/api/anomalies'
import { trainingProgress } from '@/domain/anomalies'
import { formatNumber } from '@/domain/format'
import { formatDateTime, toMillis } from '@/domain/time'
import { Badge } from '@/components/ui/Badge'

type Props = {
  status: ModelStatus
}

function Stat({ label, value, mono, hero }: { label: string; value: string; mono?: boolean; hero?: boolean }) {
  return (
    <div>
      <Text size="xs" c="dimmed">{label}</Text>
      <Text ff={mono ? 'monospace' : undefined} fw={hero ? 700 : 600} fz={hero ? 'xl' : undefined}>
        {value}
      </Text>
    </div>
  )
}

export function ModelStatusPanel({ status }: Props) {
  const progress = trainingProgress(status)

  return (
    <Card withBorder radius="md" padding={0}>
      <Card.Section withBorder inheritPadding py="xs" px="md">
        <Group justify="space-between">
          <Text fw={700} fz="sm">Modelo de detección</Text>
          {status.is_trained
            ? <Badge tone="ok">entrenado</Badge>
            : <Badge tone="warning">entrenando</Badge>}
        </Group>
      </Card.Section>

      <Card.Section inheritPadding p="md">
        <Stack gap="md">
          {/* Mientras no entrenó no hay anomalías que mostrar, y una tabla vacía se lee como «no
              pasa nada» cuando en realidad es «todavía no sé». El progreso dice cuál de las dos. */}
          {!status.is_trained && (
            <Stack gap={4}>
              <Text size="sm">
                Necesita {status.min_samples_to_train} muestras para entrenar y lleva{' '}
                {status.buffer_size}.
              </Text>
              <Progress value={progress * 100} color="brand" />
            </Stack>
          )}

          <SimpleGrid cols={2} spacing="md">
            {/* El único indicador de caudal del bus accesible por HTTP en todo el sistema: el
                detector consume todos los tópicos, el gateway no expone nada equivalente. */}
            <Stat label="Eventos vistos por el bus" value={formatNumber(status.total_events_seen)} hero />
            <Stat label="Anomalías detectadas" value={formatNumber(status.anomalies_detected)} />
            <Stat label="Contaminación esperada" value={`${(status.contamination * 100).toFixed(1)} %`} />
            <Stat label="Reentrena cada" value={`${formatNumber(status.retrain_every_n)} eventos`} />
            <Stat label="Último entrenamiento" value={formatDateTime(toMillis(status.last_trained_at))} mono />
          </SimpleGrid>
        </Stack>
      </Card.Section>
    </Card>
  )
}
