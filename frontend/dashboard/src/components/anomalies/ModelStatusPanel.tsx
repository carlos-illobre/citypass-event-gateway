import type { ModelStatus } from '@/api/anomalies'
import { trainingProgress } from '@/domain/anomalies'
import { formatNumber } from '@/domain/format'
import { formatDateTime, toMillis } from '@/domain/time'
import { Badge } from '@/components/ui/Badge'
import './ModelStatusPanel.css'

type Props = {
  status: ModelStatus
}

export function ModelStatusPanel({ status }: Props) {
  const progress = trainingProgress(status)

  return (
    <div className="card model-status">
      <div className="card-header">
        <span className="card-title">Modelo de detección</span>
        {status.is_trained
          ? <Badge tone="ok">entrenado</Badge>
          : <Badge tone="warning">entrenando</Badge>}
      </div>

      <div className="card-body">
        {/* Mientras no entrenó no hay anomalías que mostrar, y una tabla vacía se lee como «no
            pasa nada» cuando en realidad es «todavía no sé». El progreso dice cuál de las dos. */}
        {!status.is_trained && (
          <div className="model-status__training">
            <p className="model-status__training-text">
              Necesita {status.min_samples_to_train} muestras para entrenar y lleva{' '}
              {status.buffer_size}.
            </p>
            <div className="model-status__track">
              <div className="model-status__fill" style={{ width: `${progress * 100}%` }} />
            </div>
          </div>
        )}

        <dl className="model-status__list">
          <div>
            <dt>Eventos vistos por el bus</dt>
            {/* El único indicador de caudal del bus accesible por HTTP en todo el sistema: el
                detector consume todos los tópicos, el gateway no expone nada equivalente. */}
            <dd className="model-status__hero">{formatNumber(status.total_events_seen)}</dd>
          </div>
          <div>
            <dt>Anomalías detectadas</dt>
            <dd>{formatNumber(status.anomalies_detected)}</dd>
          </div>
          <div>
            <dt>Contaminación esperada</dt>
            <dd>{(status.contamination * 100).toFixed(1)} %</dd>
          </div>
          <div>
            <dt>Reentrena cada</dt>
            <dd>{formatNumber(status.retrain_every_n)} eventos</dd>
          </div>
          <div>
            <dt>Último entrenamiento</dt>
            <dd className="mono">{formatDateTime(toMillis(status.last_trained_at))}</dd>
          </div>
        </dl>
      </div>
    </div>
  )
}
