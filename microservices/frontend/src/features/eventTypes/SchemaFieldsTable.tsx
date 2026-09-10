import { Table, Text, Code, Stack, Group, Badge } from '@mantine/core'
import { formatType, type RawField } from '@/domain/avro'
import type { EventTypeSummary } from '@/api/gateway'

type FieldsTableProps = { caption: string; fields: RawField[]; muted?: boolean }

/** Campo + tipo, en una línea legible — lo que orienta a quien va a publicar sin tener
 *  que leer el JSON del schema entero. Portado de `event-gateway-ui`. */
function FieldsTable({ caption, fields, muted }: FieldsTableProps) {
  return (
    <Stack gap={4}>
      <Text size="xs" c="dimmed" fw={600}>{caption}</Text>
      <Table withTableBorder withColumnBorders={false} verticalSpacing={4}>
        <Table.Thead>
          <Table.Tr><Table.Th>Campo</Table.Th><Table.Th>Tipo</Table.Th></Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {fields.map(f => (
            <Table.Tr key={f.name} opacity={muted ? 0.6 : 1}>
              <Table.Td><Code>{f.name}</Code></Table.Td>
              <Table.Td><Code>{formatType(f.type)}</Code></Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  )
}

type Props = {
  eventType: EventTypeSummary
  data: { name: string; fields: RawField[] } | null
  metadata: { name: string; fields: RawField[] } | null
}

/**
 * El schema Avro del tipo elegido, en forma de tabla — el ejemplo que faltaba respecto de
 * `event-gateway-ui`: ahí, el selector de tipos se puede desplegar para ver exactamente
 * esto mientras se completa el formulario de al lado.
 */
export function SchemaFieldsTable({ eventType, data, metadata }: Props) {
  return (
    <Stack gap="sm">
      <Group gap="lg">
        <Text size="xs"><Text span c="dimmed" inherit>namespace</Text> <Code>{eventType.namespace}</Code></Text>
        <Text size="xs"><Text span c="dimmed" inherit>tópico</Text> <Code>{eventType.topic}</Code></Text>
        {eventType.versions.length > 1 && <Badge size="xs" variant="light">v{eventType.version} de {eventType.versions.length}</Badge>}
      </Group>

      {data
        ? <FieldsTable caption="data — campos del productor" fields={data.fields} />
        : (
          <Text size="sm" c="orange">
            Formato anterior (campos planos, sin envelope data/metadata). Hay que volver a
            registrarlo para poder publicar desde acá.
          </Text>
        )}

      {metadata && <FieldsTable caption="metadata — la calcula el gateway" fields={metadata.fields} muted />}
    </Stack>
  )
}
