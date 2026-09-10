import { describe, it, expect, vi, afterEach } from 'vitest'
import { anomalies } from './anomalies'

function stubFetch(body: unknown) {
  const fn = vi.fn().mockResolvedValue({
    ok: true, status: 200, headers: new Headers(),
    text: () => Promise.resolve(JSON.stringify(body)),
    json: () => Promise.resolve(body),
  } as Response)
  vi.stubGlobal('fetch', fn)
  return fn
}

afterEach(() => vi.unstubAllGlobals())

describe('anomalies', () => {
  it('list pide /anomaly/api/v1/anomalies con el límite, sin token', async () => {
    const fn = stubFetch({ total: 0, returned: 0, anomalies: [] })
    await anomalies.list(25)
    const [url, opts] = fn.mock.calls[0]
    expect(url).toBe('/anomaly/api/v1/anomalies?limit=25')
    expect((opts?.headers as Headers | undefined)?.has('Authorization') ?? false).toBe(false)
  })

  it('modelStatus pide /anomaly/api/v1/model/status', async () => {
    const fn = stubFetch({ is_trained: false, total_events_seen: 0, buffer_size: 0, min_samples_to_train: 50, retrain_every_n: 100, contamination: 0.05, anomalies_detected: 0, last_trained_at: null })
    await anomalies.modelStatus()
    expect(fn.mock.calls[0][0]).toBe('/anomaly/api/v1/model/status')
  })

  it('modelFeatures pide /anomaly/api/v1/model/features', async () => {
    const fn = stubFetch({ features: {} })
    await anomalies.modelFeatures()
    expect(fn.mock.calls[0][0]).toBe('/anomaly/api/v1/model/features')
  })

  it('health pide /anomaly/health', async () => {
    const fn = stubFetch({ status: 'UP', service: 'anomaly-detector' })
    await anomalies.health()
    expect(fn.mock.calls[0][0]).toBe('/anomaly/health')
  })
})
