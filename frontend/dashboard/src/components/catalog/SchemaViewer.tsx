import { useContext, useEffect, useState } from 'react'
import { Box, Button, Group, SimpleGrid, Stack, Table, Text } from '@mantine/core'
import { gateway } from '@/api/gateway'
import { AuthContext } from '@/contexts/auth-context'
import { flattenSchema, type SchemaField } from '@/domain/avro'
import { ErrorBanner } from '@/components/ui/ErrorBanner'
import { JsonView } from '@/components/ui/JsonView'
import { Badge } from '@/components/ui/Badge'

type Props = {
  fqn: string
}

function FieldTable({ fields, caption }: { fields: SchemaField[]; caption: string }) {
  if (fields.length === 0) return <Text size="sm" c="dimmed">Sin campos.</Text>
  return (
    <Table>
      <Table.Caption>{caption}</Table.Caption>
      <Table.Thead>
        <Table.Tr><Table.Th>Campo</Table.Th><Table.Th>Tipo</Table.Th><Table.Th>Notas</Table.Th></Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {fields.map(f => (
          <Table.Tr key={f.path}>
            <Table.Td>
              <Text ff="monospace" size="sm" style={{ paddingLeft: `${f.depth}rem` }}>{f.name}</Text>
            </Table.Td>
            <Table.Td><Text ff="monospace" size="sm" c="dimmed">{f.type}</Text></Table.Td>
            <Table.Td>
              <Group gap={4} wrap="wrap">
                {f.nullable && <Badge tone="neutral">opcional</Badge>}
                {f.hasDefault && <Badge tone="neutral">con default</Badge>}
                {f.doc && <Text size="sm">{f.doc}</Text>}
              </Group>
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  )
}

/**
 * El esquema de un tipo, junto con el del sobre.
 *
 * Los dos, y no sólo el primero: todo evento del bus viaja como `{ data, metadata }`, así que
 * quien está por escribir un consumidor necesita ver las dos mitades del contrato. Mostrar sólo
 * el `data` deja la mitad del trabajo para descubrir a mano.
 *
 * La consulta se dispara al expandir la fila y no se sondea: un esquema registrado no cambia —
 * el Schema Registry rechaza las versiones incompatibles— así que repreguntarlo sería gastar
 * cuota a cambio de nada.
 */
export function SchemaViewer({ fqn }: Props) {
  const { token } = useContext(AuthContext)
  const [schema, setSchema]     = useState<Record<string, unknown> | null>(null)
  const [metadata, setMetadata] = useState<Record<string, unknown> | null>(null)
  const [error, setError]       = useState('')
  const [loading, setLoading]   = useState(true)

  // `loading` arranca en true y no se vuelve a poner acá: cada fila desplegada monta su propio
  // `SchemaViewer`, así que `fqn` no cambia durante la vida del componente y no hay nada que
  // reiniciar. Ponerlo dentro del efecto sería un setState sincrónico, que dispara un render en
  // cascada — el React Compiler lo marca como error.
  useEffect(() => {
    if (!token) return
    const controller = new AbortController()
    Promise.all([
      gateway.getEventTypeSchema(token, fqn, controller.signal),
      gateway.getMetadataSchema(token, controller.signal),
    ])
      .then(([tipo, sobre]) => { setSchema(tipo); setMetadata(sobre); setError('') })
      .catch((err: Error) => { if (!controller.signal.aborted) setError(err.message) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [token, fqn])

  const copyFqn = () => { void navigator.clipboard?.writeText(fqn) }

  if (loading) return <Text size="sm" c="dimmed">Cargando el esquema…</Text>
  if (error) return <ErrorBanner message={error} />

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Text ff="monospace" size="sm">{fqn}</Text>
        <Button variant="default" size="xs" onClick={copyFqn}>Copiar FQN</Button>
      </Group>

      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <Stack gap="md">
          <FieldTable fields={flattenSchema(schema)} caption="Campos del evento (data)" />
          <FieldTable fields={flattenSchema(metadata)} caption="Campos del sobre (metadata)" />
        </Stack>
        <Box>
          <Text size="sm" fw={600} mb="xs">Esquema Avro sin procesar</Text>
          <JsonView value={schema} collapsedByDefault={path => path.split('.').length > 2} />
        </Box>
      </SimpleGrid>
    </Stack>
  )
}
