import { maxCount, type Tally } from '@/domain/tally'
import './BarList.css'

type Props = {
  data:   readonly Tally[]
  /** Cuántas filas mostrar. El resto se agrupa en «otros» para no cortar el total en silencio. */
  limit?: number
  label?: string
}

/**
 * Barras horizontales en CSS puro.
 *
 * Sin librería de gráficos: los volúmenes de este tablero son de decenas de filas y una barra es
 * un `div` con un ancho porcentual. Traer una dependencia para esto sería apartarse del estilo
 * del repositorio, que es deliberadamente magro en dependencias.
 *
 * La tabla debajo del gráfico no es redundante: es lo que hace que un lector de pantalla pueda
 * leer los valores, que un `div` con ancho porcentual no comunica.
 */
export function BarList({ data, limit = 10, label }: Props) {
  const shown = data.slice(0, limit)
  const rest  = data.slice(limit)
  const restCount = rest.reduce((sum, t) => sum + t.count, 0)
  const rows = restCount > 0
    ? [...shown, { key: `otros (${rest.length})`, count: restCount }]
    : shown

  const max = maxCount(rows)

  return (
    <table className="bar-list" aria-label={label}>
      <tbody>
        {rows.map(({ key, count }) => (
          <tr className="bar-list__row" key={key}>
            <th className="bar-list__key" scope="row" title={key}>{key}</th>
            <td className="bar-list__track">
              <div
                className="bar-list__bar"
                // El 2 % de piso deja visible una barra de valor 1 al lado de una de 200: sin
                // eso, la fila parece vacía y se lee como cero.
                style={{ width: max > 0 ? `${Math.max(2, (count / max) * 100)}%` : '0%' }}
              />
            </td>
            <td className="bar-list__count">{count}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
