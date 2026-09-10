import { useContext, useMemo, useState } from 'react'
import {
  Grid, Stack, Select, Paper, Text, Button, Group, Code, ScrollArea, Loader, Badge,
  Collapse, ActionIcon,
} from '@mantine/core'
import { IconSend, IconWand, IconEraser, IconChevronDown, IconChevronRight } from '@tabler/icons-react'
import { CodeHighlight } from '@mantine/code-highlight'
import { AuthContext } from '@/contexts/auth-context'
import { useResource } from '@/hooks/useResource'
import { gateway, type EventTypeSummary, type PublishEventResponse } from '@/api/gateway'
import { dataRecordOf, metadataRecordOf } from '@/domain/avro'
import { collectNamed, emptyValue, sampleValue, toPayload, type ValueNode } from '@/domain/value'
import { ProblemAlert } from '@/components/ui/ProblemAlert'
import { ValueEditor } from './ValueEditor'
import { SchemaFieldsTable } from '@/features/eventTypes/SchemaFieldsTable'

function PublishForm({ eventType }: { eventType: EventTypeSummary }) {
  const fqn = eventType.fqn
  const { token } = useContext(AuthContext)
  const schema = useResource((t, signal) => gateway.getEventTypeSchema(t, fqn, signal), { intervalMs: 3_600_000 })
  const [error, setError] = useState<unknown>(null)
  const [sending, setSending] = useState(false)
  const [sent, setSent] = useState<PublishEventResponse | null>(null)
  const [schemaOpen, setSchemaOpen] = useState(true)

  if (!schema.data) return <Loader size="sm" />

  const dataRecord = dataRecordOf(schema.data.fields)
  if (!dataRecord) {
    return <ProblemAlert message="Este event type tiene el formato anterior, sin envelope data/metadata. No se puede publicar desde acá." />
  }

  return (
    <Stack gap="md">
      {/* El ejemplo del schema Avro que faltaba respecto de `event-gateway-ui`: ahí se ve
          desplegando el tipo en la lista; acá queda a la vista mientras se completa el
          formulario de al lado, en vez de tener que volver al catálogo a consultarlo. */}
      <Paper withBorder p="md">
        <Group justify="space-between" style={{ cursor: 'pointer' }} onClick={() => setSchemaOpen(v => !v)}>
          <Group gap={6}>
            <ActionIcon size="sm" variant="subtle" color="gray">
              {schemaOpen ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
            </ActionIcon>
            <Text fw={600} size="sm">Schema Avro de {eventType.name}</Text>
          </Group>
        </Group>
        <Collapse expanded={schemaOpen}>
          <Stack pt="sm">
            <SchemaFieldsTable eventType={eventType} data={dataRecord} metadata={metadataRecordOf(schema.data.fields)} />
          </Stack>
        </Collapse>
      </Paper>

      <PublishFormBody key={fqn} fqn={fqn} token={token} fields={dataRecord.fields}
        error={error} setError={setError} sending={sending} setSending={setSending} sent={sent} setSent={setSent} />
    </Stack>
  )
}

function PublishFormBody({ fqn, token, fields, error, setError, sending, setSending, sent, setSent }: {
  fqn: string; token: string; fields: { name: string; type: unknown }[]
  error: unknown; setError: (e: unknown) => void
  sending: boolean; setSending: (b: boolean) => void
  sent: PublishEventResponse | null; setSent: (r: PublishEventResponse | null) => void
}) {
  const named = useMemo(() => collectNamed(fields), [fields])
  const [values, setValues] = useState<Record<string, ValueNode>>(() =>
    Object.fromEntries(fields.map(f => [f.name, emptyValue(f.type, named)])))

  const { data, issues } = useMemo(() => toPayload(fields, values, named), [fields, values, named])
  const issuesByPath = useMemo(() => {
    const map = new Map<string, string[]>()
    for (const issue of issues) {
      const list = map.get(issue.path)
      if (list) list.push(issue.message); else map.set(issue.path, [issue.message])
    }
    return map
  }, [issues])

  const fillSample = () => setValues(Object.fromEntries(fields.map(f => [f.name, sampleValue(f.type, named)])))
  const clear = () => setValues(Object.fromEntries(fields.map(f => [f.name, emptyValue(f.type, named)])))

  const submit = () => {
    if (issues.length > 0) return
    setSending(true); setError(null); setSent(null)
    gateway.publishEvent(token, fqn, data)
      .then(setSent)
      .catch(setError)
      .finally(() => setSending(false))
  }

  return (
    <Grid gap="lg">
      <Grid.Col span={{ base: 12, md: 7 }}>
        <Paper withBorder p="md">
          <Group justify="space-between" mb="md">
            <Text fw={600}>Datos del evento</Text>
            <Group gap="xs">
              <Button size="xs" variant="light" leftSection={<IconWand size={14} />} onClick={fillSample}>ejemplo</Button>
              <Button size="xs" variant="subtle" color="gray" leftSection={<IconEraser size={14} />} onClick={clear}>limpiar</Button>
            </Group>
          </Group>
          <Stack gap="sm">
            {fields.map(f => (
              <ValueEditor
                key={f.name} label={f.name} type={f.type} node={values[f.name]} named={named}
                path={f.name} issues={issuesByPath}
                onChange={next => setValues(v => ({ ...v, [f.name]: next }))}
              />
            ))}
          </Stack>
          {error !== null && (
            <ProblemAlert
              message={error instanceof Error ? error.message : String(error)}
              error={error}
              // El 400 de "no cumple el schema" es RFC 9457; se muestra abajo también, por si acaso.
            />
          )}
          <Button mt="md" fullWidth leftSection={<IconSend size={16} />} loading={sending}
            disabled={issues.length > 0} onClick={submit}>
            Publicar
          </Button>
        </Paper>
      </Grid.Col>

      <Grid.Col span={{ base: 12, md: 5 }}>
        <Stack gap="md">
          <Paper withBorder p="md">
            <Text fw={600} mb="xs">Se va a enviar</Text>
            <ScrollArea.Autosize mah={280}>
              <CodeHighlight code={JSON.stringify(data, null, 2)} language="json" />
            </ScrollArea.Autosize>
          </Paper>
          {sent && (
            <Paper withBorder p="md" style={{ borderColor: 'var(--mantine-color-teal-5)' }}>
              <Badge color="teal" mb="xs">202 · publicado</Badge>
              <Text size="sm"><Text span c="dimmed" inherit>eventId</Text> <Code>{sent.metadata.eventId}</Code></Text>
              <Text size="sm"><Text span c="dimmed" inherit>schemaId</Text> <Code>#{sent.metadata.schemaId}</Code></Text>
              <Text size="sm"><Text span c="dimmed" inherit>payloadHash</Text> <Code>{sent.metadata.payloadHash.slice(0, 16)}…</Code></Text>
            </Paper>
          )}
        </Stack>
      </Grid.Col>
    </Grid>
  )
}

/**
 * Publicar un evento con un formulario generado desde el schema del tipo elegido.
 *
 * El body del `POST` es sólo el payload de negocio — el gateway arma el envelope. El 202
 * que devuelve ya trae la metadata calculada, así que se muestra tal cual llegó en vez de
 * reconstruirla acá, que sería adivinar.
 */
export function PublishView() {
  const [fqn, setFqn] = useState<string | null>(null)
  const poll = useResource((t, signal) => gateway.listEventTypes(t, undefined, signal), { intervalMs: 60_000 })
  const eventType = poll.data?.find(t => t.fqn === fqn) ?? null

  return (
    <Stack gap="md">
      <Group justify="space-between" align="flex-end">
        <Select
          label="Event type" placeholder="Elegí un tipo para publicar" w={420}
          data={(poll.data ?? []).map(t => ({ value: t.fqn, label: t.fqn }))}
          searchable value={fqn} onChange={setFqn}
        />
      </Group>

      {!fqn && <Text c="dimmed">Elegí un tipo del catálogo para armar el formulario.</Text>}
      {fqn && !eventType && <Loader size="sm" />}
      {/* `key={fqn}`: sin esto, cambiar de tipo no remonta `PublishForm` y el schema
          consultado —`useResource` con un intervalo de una hora— se queda pegado al
          primero que se eligió en la sesión. */}
      {eventType && <PublishForm key={fqn} eventType={eventType} />}
    </Stack>
  )
}
