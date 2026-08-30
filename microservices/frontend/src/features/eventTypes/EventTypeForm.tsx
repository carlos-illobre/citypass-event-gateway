import { Suspense, lazy, useContext, useMemo, useState, type FormEvent } from 'react'
import {
  Box, Group, Stack, TextInput, Button, SegmentedControl, Text, Code, Paper, Loader, List,
} from '@mantine/core'
import { AuthContext } from '@/contexts/auth-context'
import { gateway, type EventTypeSummary, type SchemaChangeResult } from '@/api/gateway'
import { ProblemAlert } from '@/components/ui/ProblemAlert'
import { FieldListBuilder } from './FieldBuilder'
import {
  collectRefScopes, makeField, toAvroFields, validate,
  type FieldDef, type Issue,
} from '@/domain/avro'

/**
 * El editor de JSON arrastra CodeMirror, bastante más pesado que el resto de la app, así
 * que se carga bajo demanda: quien se queda en el modo visual no lo descarga.
 */
const loadJsonEditor = () => import('./JsonFieldsEditor')
const JsonFieldsEditor = lazy(loadJsonEditor)
/** Empieza a bajar el chunk al apuntar el botón, sin esperar el clic. */
const prefetchJsonEditor = () => { loadJsonEditor().catch(() => {}) }

type Mode = 'visual' | 'json'

type Props = {
  name: string
  fields: FieldDef[]
  onName: (name: string) => void
  onFields: (fields: FieldDef[]) => void
  onCreated: () => void
  /** El event type en edición, o null para crear uno nuevo. Mismo formulario para las dos cosas. */
  editing?: EventTypeSummary | null
  onCancelEdit?: () => void
}

/**
 * Qué pasó al guardar un cambio de schema — lo decide el Schema Registry, no el formulario.
 * `subscriptionsOnPreviousVersion` es lo que convierte una ruptura de contrato en una
 * decisión consciente en vez de un número que nadie leyó.
 */
function ChangeResultBanner({ result, onClose }: { result: SchemaChangeResult; onClose: () => void }) {
  const dejados = result.subscriptionsOnPreviousVersion ?? 0
  const color = result.unchanged ? 'gray' : result.breaking ? 'orange' : 'teal'

  return (
    <Paper withBorder p="md" style={{ borderColor: `var(--mantine-color-${color}-5)` }}>
      <Group justify="space-between" mb={4}>
        <Text fw={600} c={color}>
          {result.unchanged ? 'Sin cambios'
            : result.breaking ? `Cambio incompatible — versión ${result.version}`
            : 'Cambio compatible aplicado'}
        </Text>
        <Button variant="subtle" size="xs" color="gray" onClick={onClose}>cerrar</Button>
      </Group>
      <Text size="sm" c="dimmed" mb="xs">
        {result.unchanged
          ? 'El schema que mandaste es idéntico al que ya estaba, así que no se registró nada nuevo.'
          : result.breaking
            ? 'El contrato cambió de forma incompatible: los eventos nuevos van a un tópico nuevo. La versión anterior sigue viva sirviendo su historial, para que los consumidores migren cuando puedan.'
            : 'El Schema Registry aceptó el cambio como compatible: mismo tópico, mismas suscripciones. Ningún consumidor se entera.'}
      </Text>
      <Group gap="lg">
        <Text size="xs"><Text span c="dimmed" inherit>tópico</Text> <Code>{result.topic}</Code></Text>
        {result.previousTopic && (
          <Text size="xs"><Text span c="dimmed" inherit>anterior</Text> <Code>{result.previousTopic}</Code></Text>
        )}
        <Text size="xs"><Text span c="dimmed" inherit>schemaId</Text> <Code>#{result.schemaId}</Code></Text>
      </Group>
      {result.breaking && dejados > 0 && (
        <Text size="xs" c="orange" mt="xs">
          {dejados} {dejados === 1 ? 'suscripción quedó' : 'suscripciones quedaron'} en el tópico
          anterior y ya no {dejados === 1 ? 'va' : 'van'} a recibir eventos nuevos. El gateway avisó
          del cambio en <Code>com.citypass.gateway.EsquemaCambiado</Code>, pero conviene coordinar
          la migración con esos equipos.
        </Text>
      )}
    </Paper>
  )
}

function ValidationSummary({ issues }: { issues: Issue[] }) {
  return (
    <Paper withBorder p="sm" bg="red.0" style={{ borderColor: 'var(--mantine-color-red-4)' }}>
      <Text size="sm" fw={600} c="red">
        {issues.length} {issues.length === 1 ? 'problema' : 'problemas'} por resolver
      </Text>
      <List size="sm" c="red">
        {issues.map((issue, i) => <List.Item key={i}>{issue.message}</List.Item>)}
      </List>
    </Paper>
  )
}

export function EventTypeForm({ name, fields, onName, onFields, onCreated, editing = null, onCancelEdit }: Props) {
  const { token } = useContext(AuthContext)
  const [error, setError]     = useState<unknown>(null)
  const [loading, setLoading] = useState(false)
  const [touched, setTouched] = useState(false)
  const [mode, setMode]       = useState<Mode>('visual')
  const [change, setChange]   = useState<SchemaChangeResult | null>(null)

  const issues    = useMemo(() => validate(name, fields), [name, fields])
  const refScopes = useMemo(() => collectRefScopes(fields), [fields])

  const issuesByField = useMemo(() => {
    const map = new Map<string, Issue[]>()
    for (const issue of issues) {
      if (!issue.fieldId) continue
      const list = map.get(issue.fieldId)
      if (list) list.push(issue); else map.set(issue.fieldId, [issue])
    }
    return map
  }, [issues])

  const editFields = (next: FieldDef[]) => { onFields(next); setTouched(true) }
  const reset = () => { onName(''); onFields([makeField()]); setTouched(false) }

  const handleSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    setTouched(true)
    if (issues.length > 0) return

    setError(null); setChange(null); setLoading(true)
    const avroFields = toAvroFields(fields)

    const request = editing
      // El FQN viaja en la ruta: el nombre del formulario no puede renombrar nada.
      ? gateway.updateEventType(token, editing.fqn, avroFields).then(result => {
          setChange(result)
          // No se limpia el formulario: quien acaba de cambiar un schema suele querer ver
          // contra qué quedó, y encima puede necesitar corregir otra cosa.
          onCreated()
        })
      : gateway.createEventType(token, { name: name.trim(), fields: avroFields }).then(() => {
          reset(); onCreated()
        })

    request.catch(setError).finally(() => setLoading(false))
  }

  return (
    <Box component="form" onSubmit={handleSubmit}>
      <Stack gap="md">
        <Group justify="space-between">
          <Text fw={700} size="lg">{editing ? 'Editar event type' : 'Nuevo event type'}</Text>
          {editing && (
            <Button variant="subtle" color="gray" size="xs"
              onClick={() => { setChange(null); setError(null); onCancelEdit?.() }}>
              Cancelar edición
            </Button>
          )}
        </Group>

        {editing && (
          <Text size="sm" c="dimmed">
            Estás cambiando el schema de <Code>{editing.fqn}</Code>. Se envía la lista{' '}
            <Text span fw={600} inherit>completa</Text> de campos: lo que borres acá, se borra
            del contrato. Si el cambio resulta incompatible, el gateway estrena una versión
            nueva y deja la actual sirviendo su historial.
          </Text>
        )}

        <TextInput
          label="Nombre"
          description={editing ? 'No se puede cambiar: identifica al event type' : 'El namespace se toma del JWT'}
          placeholder="ej: BiciDevuelta"
          value={name}
          disabled={editing !== null}
          onChange={e => onName(e.target.value)}
          onBlur={() => setTouched(true)}
        />

        <Box>
          <Group justify="space-between" mb="xs">
            <Text size="sm" fw={600}>Campos</Text>
            <SegmentedControl
              size="xs" value={mode} onChange={v => setMode(v as Mode)}
              data={[{ label: 'Visual', value: 'visual' }, { label: 'JSON', value: 'json' }]}
              onMouseEnter={prefetchJsonEditor} onFocus={prefetchJsonEditor}
            />
          </Group>

          {mode === 'visual' && (
            <FieldListBuilder
              fields={fields} onChange={editFields} refScopes={refScopes}
              issuesByField={touched ? issuesByField : new Map()}
            />
          )}

          {mode === 'json' && (
            <Suspense fallback={<Loader size="sm" />}>
              <JsonFieldsEditor fields={fields} onChange={editFields} />
            </Suspense>
          )}
        </Box>

        {touched && issues.length > 0 && <ValidationSummary issues={issues} />}
        {error !== null && <ProblemAlert message={error instanceof Error ? error.message : String(error)} error={error} />}
        {change && <ChangeResultBanner result={change} onClose={() => setChange(null)} />}

        <Button type="submit" loading={loading} disabled={touched && issues.length > 0}>
          {editing ? 'Guardar cambios' : 'Registrar'}
        </Button>
      </Stack>
    </Box>
  )
}
