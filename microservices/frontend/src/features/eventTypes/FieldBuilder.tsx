import { useRef } from 'react'
import {
  Box, Group, Stack, TextInput, NumberInput, Select, ActionIcon, Text, Badge,
  Checkbox, Button, Paper,
} from '@mantine/core'
import {
  IconChevronRight, IconArrowUp, IconArrowDown, IconCopy, IconX, IconPlus,
} from '@tabler/icons-react'
import {
  KIND_LABELS, LOGICAL_TYPES, PRIMITIVES,
  cloneField, defaultTypeDef, makeField, namedInside, replaceNamedInside,
  type FieldDef, type Issue, type Kind, type LogicalType, type Primitive, type TypeDef,
} from '@/domain/avro'

// ── TypeControls ────────────────────────────────────────────────────────────
// Selectores inline. La expansión de record/enum la maneja FieldListBuilder.

type TypeControlsProps = { typeDef: TypeDef; onChange: (t: TypeDef) => void; refOptions: string[] }

function TypeControls({ typeDef, onChange, refOptions }: TypeControlsProps) {
  // Recuerda el subárbol de cada kind para no perderlo al cambiar de tipo.
  const memo = useRef<Partial<Record<Kind, TypeDef>>>({})

  const changeKind = (next: Kind) => {
    memo.current[typeDef.kind] = typeDef
    onChange(memo.current[next] ?? defaultTypeDef(next))
  }

  const kinds: Kind[] = refOptions.length > 0
    ? ['primitive', 'logical', 'array', 'map', 'record', 'enum', 'ref']
    : ['primitive', 'logical', 'array', 'map', 'record', 'enum']

  return (
    <Group gap={4} wrap="nowrap">
      <Select
        size="xs" w={104} aria-label="Tipo" data={kinds.map(k => ({ value: k, label: KIND_LABELS[k] }))}
        value={typeDef.kind} onChange={v => v && changeKind(v as Kind)} allowDeselect={false}
      />

      {typeDef.kind === 'primitive' && (
        <Select
          size="xs" w={90} aria-label="Primitivo" data={PRIMITIVES}
          value={typeDef.primitive} allowDeselect={false}
          onChange={v => v && onChange({ kind: 'primitive', primitive: v as Primitive })}
        />
      )}

      {typeDef.kind === 'logical' && (
        <>
          <Select
            size="xs" w={140} aria-label="Tipo lógico" data={LOGICAL_TYPES} allowDeselect={false}
            value={typeDef.logical}
            onChange={v => v && onChange({ ...typeDef, logical: v as LogicalType })}
          />
          {typeDef.logical === 'decimal' && (
            <Group gap={4} wrap="nowrap">
              <NumberInput size="xs" w={56} min={1} label="p" value={typeDef.precision}
                onChange={v => onChange({ ...typeDef, precision: Number(v) || 1 })} />
              <NumberInput size="xs" w={56} min={0} label="s" value={typeDef.scale}
                onChange={v => onChange({ ...typeDef, scale: Number(v) || 0 })} />
            </Group>
          )}
        </>
      )}

      {typeDef.kind === 'array' && (
        <>
          <Text size="xs" c="dimmed">de</Text>
          <TypeControls typeDef={typeDef.items} onChange={items => onChange({ kind: 'array', items })} refOptions={refOptions} />
        </>
      )}

      {typeDef.kind === 'map' && (
        <>
          <Text size="xs" c="dimmed">de</Text>
          <TypeControls typeDef={typeDef.values} onChange={values => onChange({ kind: 'map', values })} refOptions={refOptions} />
        </>
      )}

      {typeDef.kind === 'ref' && (
        <Select
          size="xs" w={160} aria-label="Tipo referenciado" placeholder="— elegir —"
          data={refOptions} value={typeDef.refName || null}
          onChange={v => onChange({ kind: 'ref', refName: v ?? '' })}
        />
      )}
    </Group>
  )
}

// ── Paneles de expansión ─────────────────────────────────────────────────────

function RecordPanel({
  recordName, fields, invalid, refScopes, issuesByField, onNameChange, onFieldsChange,
}: {
  recordName: string; fields: FieldDef[]; invalid: boolean
  refScopes: Map<string, string[]>; issuesByField: Map<string, Issue[]>
  onNameChange: (name: string) => void; onFieldsChange: (fields: FieldDef[]) => void
}) {
  return (
    <Paper withBorder p="sm" mt={4} bg="gray.0">
      <TextInput
        size="xs" label="Nombre del record" placeholder="ej: Ubicacion" mb="xs"
        value={recordName} error={invalid} onChange={e => onNameChange(e.target.value)}
      />
      <FieldListBuilder fields={fields} onChange={onFieldsChange} refScopes={refScopes} issuesByField={issuesByField} nested />
    </Paper>
  )
}

function EnumPanel({
  enumName, symbols, invalid, onNameChange, onSymbolsChange,
}: {
  enumName: string; symbols: string[]; invalid: boolean
  onNameChange: (name: string) => void; onSymbolsChange: (symbols: string[]) => void
}) {
  const setAt = (i: number, v: string) => onSymbolsChange(symbols.map((s, j) => j === i ? v : s))
  const removeAt = (i: number) => onSymbolsChange(symbols.filter((_, j) => j !== i))

  return (
    <Paper withBorder p="sm" mt={4} bg="gray.0">
      <TextInput
        size="xs" label="Nombre del enum" placeholder="ej: Estado" mb="xs"
        value={enumName} error={invalid} onChange={e => onNameChange(e.target.value)}
      />
      <Stack gap={4}>
        {symbols.map((s, i) => (
          <Group key={i} gap={4} wrap="nowrap">
            <TextInput size="xs" flex={1} placeholder="SIMBOLO" value={s} onChange={e => setAt(i, e.target.value)} />
            <ActionIcon size="sm" variant="subtle" color="red" disabled={symbols.length === 1}
              onClick={() => removeAt(i)} aria-label="Quitar símbolo">
              <IconX size={12} />
            </ActionIcon>
          </Group>
        ))}
        <Button size="xs" variant="subtle" leftSection={<IconPlus size={14} />} onClick={() => onSymbolsChange([...symbols, ''])}>
          símbolo
        </Button>
      </Stack>
    </Paper>
  )
}

// ── FieldListBuilder ─────────────────────────────────────────────────────────

type Props = {
  fields: FieldDef[]; onChange: (fields: FieldDef[]) => void
  refScopes: Map<string, string[]>; issuesByField: Map<string, Issue[]>
  nested?: boolean
}

/**
 * Constructor visual de campos Avro, recursivo. Portado de `event-gateway-ui`: la lógica
 * —memoria por kind al cambiar de tipo, orden de referencias, colapsado por campo— es
 * idéntica; sólo los controles cambiaron de HTML a Mantine.
 */
export function FieldListBuilder({ fields, onChange, refScopes, issuesByField, nested = false }: Props) {
  const add       = () => onChange([...fields, makeField()])
  const remove    = (id: string) => onChange(fields.filter(f => f.id !== id))
  const duplicate = (id: string) => {
    const i = fields.findIndex(f => f.id === id)
    if (i < 0) return
    const copy = [...fields]
    copy.splice(i + 1, 0, cloneField(fields[i]))
    onChange(copy)
  }
  const move = (id: string, delta: -1 | 1) => {
    const i = fields.findIndex(f => f.id === id)
    const j = i + delta
    if (i < 0 || j < 0 || j >= fields.length) return
    const copy = [...fields]
    ;[copy[i], copy[j]] = [copy[j], copy[i]]
    onChange(copy)
  }
  const update = (id: string, patch: Partial<FieldDef>) =>
    onChange(fields.map(f => f.id === id ? { ...f, ...patch } : f))

  return (
    <Stack gap="xs" pl={nested ? 0 : undefined}>
      {fields.map((field, index) => {
        const td         = field.typeDef
        const refOptions = refScopes.get(field.id) ?? []
        const hasIssue   = (issuesByField.get(field.id)?.length ?? 0) > 0
        const named      = namedInside(td)
        const open       = named !== null && !field.collapsed

        const setNamed = (next: typeof named) =>
          next && update(field.id, { typeDef: replaceNamedInside(td, next) })

        const summary =
          named?.kind === 'record' ? `${named.fields.length} campo${named.fields.length === 1 ? '' : 's'}`
          : named?.kind === 'enum' ? `${named.symbols.filter(s => s.trim()).length} símbolos`
          : ''

        return (
          <Box key={field.id}>
            <Group gap={6} wrap="nowrap" align="center">
              {named ? (
                <ActionIcon size="sm" variant="subtle" color="gray"
                  onClick={() => update(field.id, { collapsed: !field.collapsed })}
                  aria-expanded={open} aria-label={open ? 'Plegar' : 'Desplegar'}>
                  <IconChevronRight size={14} style={{ transform: open ? 'rotate(90deg)' : undefined, transition: 'transform 120ms' }} />
                </ActionIcon>
              ) : <Box w={28} />}

              <TextInput
                size="xs" w={140} placeholder="nombre" value={field.name} error={hasIssue}
                onChange={e => update(field.id, { name: e.target.value })}
              />

              <TypeControls typeDef={td} onChange={typeDef => update(field.id, { typeDef })} refOptions={refOptions} />

              {named && field.collapsed && <Badge size="xs" variant="light" color="gray">{summary}</Badge>}

              <Checkbox size="xs" label="opcional" checked={field.nullable}
                onChange={e => update(field.id, { nullable: e.currentTarget.checked })} title="El campo admite null" />

              <Group gap={2} ml="auto" wrap="nowrap">
                <ActionIcon size="sm" variant="subtle" onClick={() => move(field.id, -1)} disabled={index === 0} aria-label="Subir">
                  <IconArrowUp size={13} />
                </ActionIcon>
                <ActionIcon size="sm" variant="subtle" onClick={() => move(field.id, 1)} disabled={index === fields.length - 1} aria-label="Bajar">
                  <IconArrowDown size={13} />
                </ActionIcon>
                <ActionIcon size="sm" variant="subtle" onClick={() => duplicate(field.id)} aria-label="Duplicar">
                  <IconCopy size={13} />
                </ActionIcon>
                <ActionIcon size="sm" variant="subtle" color="red" onClick={() => remove(field.id)}
                  disabled={fields.length === 1 && !nested} aria-label="Eliminar campo">
                  <IconX size={13} />
                </ActionIcon>
              </Group>
            </Group>

            {open && named.kind === 'record' && (
              <Box ml={28}>
                <RecordPanel
                  recordName={named.recordName} fields={named.fields} invalid={!named.recordName.trim()}
                  refScopes={refScopes} issuesByField={issuesByField}
                  onNameChange={n => setNamed({ ...named, recordName: n })}
                  onFieldsChange={fs => setNamed({ ...named, fields: fs })}
                />
              </Box>
            )}

            {open && named.kind === 'enum' && (
              <Box ml={28}>
                <EnumPanel
                  enumName={named.enumName} symbols={named.symbols} invalid={!named.enumName.trim()}
                  onNameChange={n => setNamed({ ...named, enumName: n })}
                  onSymbolsChange={ss => setNamed({ ...named, symbols: ss })}
                />
              </Box>
            )}
          </Box>
        )
      })}

      <Button size="xs" variant="light" leftSection={<IconPlus size={14} />} onClick={add}>
        agregar campo
      </Button>
    </Stack>
  )
}
