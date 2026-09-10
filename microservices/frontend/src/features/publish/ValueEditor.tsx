import { useState, type ReactNode } from 'react'
import {
  Box, Fieldset, Group, Text, Badge, ActionIcon, TextInput, NumberInput, Select,
  Textarea, Button, Stack, Checkbox,
} from '@mantine/core'
import { IconChevronDown, IconChevronRight, IconArrowUp, IconArrowDown, IconX, IconPlus } from '@tabler/icons-react'
import {
  newEntry, newItem, resolveType,
  type NamedTypes, type Shape, type ValueNode,
} from '@/domain/value'

type Props = {
  label:    string
  type:     unknown
  node:     ValueNode
  named:    NamedTypes
  path:     string
  /** Mensajes de error por path, para marcar el campo exacto que falla. */
  issues:   ReadonlyMap<string, string[]>
  onChange: (node: ValueNode) => void
  /** Un ítem de array o entrada de map trae sus propios controles de borrado. */
  actions?: ReactNode
}

/** Etiqueta corta del tipo, para orientar sin abrir el schema. */
function typeBadge(shape: Shape, nullable: boolean): string {
  const base =
    shape.kind === 'record' ? (shape.name || 'record')
    : shape.kind === 'array' ? 'lista'
    : shape.kind === 'map'   ? 'mapa'
    : shape.kind === 'enum'  ? 'opciones'
    : shape.kind === 'number' ? (shape.integer ? 'entero' : 'decimal')
    : shape.kind === 'datetime' ? 'fecha y hora'
    : shape.kind === 'date' ? 'fecha'
    : shape.kind === 'time' ? 'hora'
    : shape.kind === 'boolean' ? 'sí/no'
    : shape.kind === 'json' ? 'JSON'
    : 'texto'
  return nullable ? `${base} · opcional` : base
}

/**
 * Formulario generado a partir de un tipo Avro, recursivamente.
 *
 * Es un formulario, no un editor de JSON: cada hoja tiene el control HTML que corresponde a
 * su tipo (número, fecha, selector de enum...), y la estructura —record, array, map,
 * nullable— se resuelve bajando por `resolveType`, que ya sabe seguir referencias a tipos
 * nombrados. Portado de `event-gateway-ui`, con los controles a mano cambiados por Mantine.
 */
export function ValueEditor({ label, type, node, named, path, issues, onChange, actions }: Props) {
  const { shape, nullable } = resolveType(type, named)
  const [open, setOpen] = useState(true)

  // Nivel opcional: envuelve al editor real y preserva lo cargado al marcar «null».
  if (node.kind === 'nullable') {
    const setNull = (isNull: boolean) => onChange({ ...node, isNull })
    return (
      <Box>
        <Checkbox
          size="xs" label="sin valor (null)"
          checked={node.isNull}
          onChange={e => setNull(e.currentTarget.checked)}
          mb={4}
        />
        {!node.isNull && (
          <ValueEditor
            label={label} type={type} node={node.inner} named={named}
            path={path} issues={issues}
            onChange={inner => onChange({ ...node, inner })}
            actions={actions}
          />
        )}
        {node.isNull && <Text size="sm" c="dimmed">{label} — sin valor</Text>}
      </Box>
    )
  }

  const errors = issues.get(path) ?? []

  const legend = (extra?: string) => (
    <Group gap={6} wrap="nowrap">
      <ActionIcon size="xs" variant="subtle" color="gray" onClick={() => setOpen(v => !v)}>
        {open ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
      </ActionIcon>
      <Text size="sm" fw={500}>{label}</Text>
      <Badge size="xs" variant="light" color="gray">{typeBadge(shape, nullable)}{extra ?? ''}</Badge>
      {actions}
    </Group>
  )

  // ── Record: grupo colapsable con los campos adentro ──
  if (node.kind === 'record' && shape.kind === 'record') {
    return (
      <Fieldset legend={legend()} p="sm" style={errors.length ? { borderColor: 'var(--mantine-color-red-5)' } : undefined}>
        {open && (
          <Stack gap="sm" pl="md">
            {shape.fields.map(field => (
              <ValueEditor
                key={field.name}
                label={field.name}
                type={field.type}
                node={node.fields[field.name]}
                named={named}
                path={`${path}.${field.name}`}
                issues={issues}
                onChange={next => onChange({ ...node, fields: { ...node.fields, [field.name]: next } })}
              />
            ))}
          </Stack>
        )}
      </Fieldset>
    )
  }

  // ── Array: lista de ítems con alta, baja y reordenamiento ──
  if (node.kind === 'array' && shape.kind === 'array') {
    const setItems = (items: typeof node.items) => onChange({ ...node, items })
    const move = (from: number, to: number) => {
      if (to < 0 || to >= node.items.length) return
      const items = [...node.items]
      const [moved] = items.splice(from, 1)
      items.splice(to, 0, moved)
      setItems(items)
    }

    return (
      <Fieldset legend={legend(` · ${node.items.length}`)} p="sm">
        {open && (
          <Stack gap="sm" pl="md">
            {node.items.length === 0 && <Text size="sm" c="dimmed" fs="italic">Lista vacía.</Text>}
            {node.items.map((item, i) => (
              <ValueEditor
                key={item.id}
                label={`${i}`}
                type={shape.items}
                node={item.node}
                named={named}
                path={`${path}[${i}]`}
                issues={issues}
                onChange={next => setItems(node.items.map(x => x.id === item.id ? { ...x, node: next } : x))}
                actions={
                  <Group gap={2}>
                    <ActionIcon size="xs" variant="subtle" onClick={() => move(i, i - 1)} disabled={i === 0} aria-label="Subir">
                      <IconArrowUp size={12} />
                    </ActionIcon>
                    <ActionIcon size="xs" variant="subtle" onClick={() => move(i, i + 1)} disabled={i === node.items.length - 1} aria-label="Bajar">
                      <IconArrowDown size={12} />
                    </ActionIcon>
                    <ActionIcon size="xs" variant="subtle" color="red"
                      onClick={() => setItems(node.items.filter(x => x.id !== item.id))} aria-label="Quitar">
                      <IconX size={12} />
                    </ActionIcon>
                  </Group>
                }
              />
            ))}
            <Button size="xs" variant="light" leftSection={<IconPlus size={14} />}
              onClick={() => setItems([...node.items, newItem(shape.items, named)])}>
              agregar ítem
            </Button>
          </Stack>
        )}
      </Fieldset>
    )
  }

  // ── Map: pares clave/valor ──
  if (node.kind === 'map' && shape.kind === 'map') {
    const setEntries = (entries: typeof node.entries) => onChange({ ...node, entries })

    return (
      <Fieldset legend={legend(` · ${node.entries.length}`)} p="sm">
        {open && (
          <Stack gap="sm" pl="md">
            {node.entries.length === 0 && <Text size="sm" c="dimmed" fs="italic">Mapa vacío.</Text>}
            {node.entries.map(entry => (
              <Group key={entry.id} align="flex-start" gap="xs" wrap="nowrap">
                <TextInput
                  size="xs" placeholder="clave" value={entry.key} w={140}
                  onChange={e => setEntries(node.entries.map(x => x.id === entry.id ? { ...x, key: e.target.value } : x))}
                />
                <Box style={{ flex: 1 }}>
                  <ValueEditor
                    label="valor" type={shape.values} node={entry.node} named={named}
                    path={`${path}.${entry.key || '?'}`} issues={issues}
                    onChange={next => setEntries(node.entries.map(x => x.id === entry.id ? { ...x, node: next } : x))}
                    actions={
                      <ActionIcon size="xs" variant="subtle" color="red"
                        onClick={() => setEntries(node.entries.filter(x => x.id !== entry.id))} aria-label="Quitar">
                        <IconX size={12} />
                      </ActionIcon>
                    }
                  />
                </Box>
              </Group>
            ))}
            <Button size="xs" variant="light" leftSection={<IconPlus size={14} />}
              onClick={() => setEntries([...node.entries, newEntry(shape.values, named)])}>
              agregar entrada
            </Button>
          </Stack>
        )}
        {errors.map((message, i) => <Text key={i} size="xs" c="red" role="alert">{message}</Text>)}
      </Fieldset>
    )
  }

  // ── Hojas ──
  if (node.kind !== 'scalar') return null
  const set = (raw: string) => onChange({ kind: 'scalar', raw })
  const errorText = errors[0]

  let control: ReactNode
  if (shape.kind === 'boolean') {
    control = (
      <Select size="sm" data={['true', 'false']} value={node.raw || null}
        onChange={v => set(v ?? '')} error={!!errorText} />
    )
  } else if (shape.kind === 'enum') {
    control = (
      <Select size="sm" data={shape.symbols} placeholder="— elegir —" value={node.raw || null}
        onChange={v => set(v ?? '')} error={!!errorText} />
    )
  } else if (shape.kind === 'json') {
    control = (
      <Textarea size="sm" value={node.raw} onChange={e => set(e.target.value)}
        placeholder="{}" rows={3} autosize spellCheck={false}
        styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)' } }} error={!!errorText} />
    )
  } else if (shape.kind === 'number') {
    control = (
      <NumberInput size="sm" value={node.raw} onChange={v => set(String(v ?? ''))}
        allowDecimal={!shape.integer} error={!!errorText} />
    )
  } else if (shape.kind === 'date' || shape.kind === 'time' || shape.kind === 'datetime') {
    const htmlType = shape.kind === 'datetime' ? 'datetime-local' : shape.kind
    control = (
      <TextInput size="sm" type={htmlType} value={node.raw} onChange={e => set(e.target.value)} error={!!errorText} />
    )
  } else {
    control = <TextInput size="sm" value={node.raw} onChange={e => set(e.target.value)} error={!!errorText} />
  }

  return (
    <Box>
      {legend()}
      <Box mt={4}>{control}</Box>
      {errorText && <Text size="xs" c="red" role="alert" mt={2}>{errorText}</Text>}
    </Box>
  )
}
