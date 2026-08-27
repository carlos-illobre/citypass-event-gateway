import { SCORE_FLOOR, scoreRatio, severityOf } from '@/domain/anomalies'
import './ScoreBar.css'

type Props = {
  score: number
}

/**
 * El score de IsolationForest, en escala fija.
 *
 * Es negativo y más negativo es más anómalo, así que la barra se llena hacia la izquierda desde
 * el 0. La escala es fija —no relativa al máximo de la tanda— porque si no, la anomalía más leve
 * de una lista tranquila se vería igual de grave que la peor de una lista crítica.
 */
export function ScoreBar({ score }: Props) {
  const severity = severityOf(score)
  return (
    <div className="score-bar" title={`Escala de 0 a ${SCORE_FLOOR}`}>
      <div className="score-bar__track">
        <div
          className={`score-bar__fill score-bar__fill--${severity}`}
          style={{ width: `${scoreRatio(score) * 100}%` }}
        />
      </div>
      <span className="score-bar__value">{score.toFixed(4)}</span>
    </div>
  )
}
