import { useState, type ReactNode } from 'react'
import './DataTable.css'

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
    <div className="data-table__wrap">
      <table className="data-table">
        <thead>
          <tr>
            {expanded && <th className="data-table__toggle-col" />}
            {columns.map(c => (
              <th key={c.key} style={c.width ? { width: c.width } : undefined}>{c.header}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map(row => {
            const key = rowKey(row)
            const isOpen = open.has(key)
            return [
              <tr
                key={key}
                className={expanded ? 'data-table__row--clickable' : undefined}
                onClick={expanded ? () => toggle(key) : undefined}
              >
                {expanded && (
                  <td className="data-table__toggle-col">
                    <button
                      type="button"
                      className="data-table__toggle"
                      aria-expanded={isOpen}
                      aria-label={isOpen ? 'Colapsar fila' : 'Expandir fila'}
                      onClick={e => { e.stopPropagation(); toggle(key) }}
                    >
                      {isOpen ? '▾' : '▸'}
                    </button>
                  </td>
                )}
                {columns.map(c => <td key={c.key}>{c.render(row)}</td>)}
              </tr>,
              expanded && isOpen && (
                <tr key={`${key} detalle`} className="data-table__detail">
                  <td colSpan={columns.length + 1}>{expanded(row)}</td>
                </tr>
              ),
            ]
          })}
        </tbody>
      </table>
    </div>
  )
}
