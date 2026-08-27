import { useState, type ReactNode } from 'react'
import { ActionIcon, Table } from '@mantine/core'

export type Column<T> = {
  key:    string
  header: string
  render: (row: T) => ReactNode
  /** Ancho fijo para columnas monoespaciadas, así la tabla no baila al llegar datos nuevos. */
  width?: string
}

type Props<T> = {
  rows:     readonly T[]
  columns:  readonly Column<T>[]
  rowKey:   (row: T) => string
  /** Si se pasa, cada fila se puede desplegar y muestra esto abajo. */
  expanded?: (row: T) => ReactNode
  empty:    ReactNode
}

export function DataTable<T>({ rows, columns, rowKey, expanded, empty }: Props<T>) {
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set())

  const toggle = (key: string) => setOpen(prev => {
    const next = new Set(prev)
    if (!next.delete(key)) next.add(key)
    return next
  })

  if (rows.length === 0) return <>{empty}</>

  return (
    <Table.ScrollContainer minWidth={480}>
      <Table verticalSpacing="xs" highlightOnHover={!!expanded}>
        <Table.Thead>
          <Table.Tr>
            {expanded && <Table.Th w={36} />}
            {columns.map(c => (
              <Table.Th key={c.key} style={c.width ? { width: c.width } : undefined}>{c.header}</Table.Th>
            ))}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map(row => {
            const key = rowKey(row)
            const isOpen = open.has(key)
            return [
              <Table.Tr
                key={key}
                style={expanded ? { cursor: 'pointer' } : undefined}
                onClick={expanded ? () => toggle(key) : undefined}
              >
                {expanded && (
                  <Table.Td>
                    <ActionIcon
                      variant="subtle"
                      color="gray"
                      size="sm"
                      aria-expanded={isOpen}
                      aria-label={isOpen ? 'Colapsar fila' : 'Expandir fila'}
                      onClick={e => { e.stopPropagation(); toggle(key) }}
                    >
                      {isOpen ? '▾' : '▸'}
                    </ActionIcon>
                  </Table.Td>
                )}
                {columns.map(c => <Table.Td key={c.key}>{c.render(row)}</Table.Td>)}
              </Table.Tr>,
              expanded && isOpen && (
                <Table.Tr key={`${key} detalle`}>
                  <Table.Td colSpan={columns.length + 1}>{expanded(row)}</Table.Td>
                </Table.Tr>
              ),
            ]
          })}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  )
}
