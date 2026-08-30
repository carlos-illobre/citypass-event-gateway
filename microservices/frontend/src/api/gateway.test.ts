import { describe, it, expect, vi, afterEach } from 'vitest'
import { gateway, BACKUP_FORMAT_VERSION } from './gateway'

function stubFetch(body: unknown, status = 200) {
  const fn = vi.fn().mockResolvedValue({
    ok: status < 400, status, statusText: 'OK', headers: new Headers(),
    text: () => Promise.resolve(JSON.stringify(body)),
    json: () => Promise.resolve(body),
  } as Response)
  vi.stubGlobal('fetch', fn)
  return fn
}

afterEach(() => vi.unstubAllGlobals())

describe('gateway', () => {
  it('listEventTypes sin namespace pide el listado entero', async () => {
    const fn = stubFetch([])
    await gateway.listEventTypes('tok')
    expect(fn.mock.calls[0][0]).toBe('/api/v1/event-types')
  })

  it('listEventTypes con namespace lo codifica en la query', async () => {
    const fn = stubFetch([])
    await gateway.listEventTypes('tok', 'com.citypass.movilidad')
    expect(fn.mock.calls[0][0]).toBe('/api/v1/event-types?namespace=com.citypass.movilidad')
  })

  it('listMyEvents arma la URL con el límite', async () => {
    const fn = stubFetch({ returned: 0, topicsScanned: 0, events: [] })
    await gateway.listMyEvents('tok', 25)
    expect(fn.mock.calls[0][0]).toBe('/api/v1/events?limit=25')
  })

  it('getQuota pide /event-types/quota', async () => {
    const fn = stubFetch({})
    await gateway.getQuota('tok')
    expect(fn.mock.calls[0][0]).toBe('/api/v1/event-types/quota')
  })

  it('exportBackup pide /event-types/export', async () => {
    const fn = stubFetch({ formatVersion: BACKUP_FORMAT_VERSION, namespace: 'x', exportedAt: '', eventTypes: [] })
    await gateway.exportBackup('tok')
    expect(fn.mock.calls[0][0]).toBe('/api/v1/event-types/export')
  })

  it('getEventTypeSchema codifica el FQN en la ruta', async () => {
    const fn = stubFetch({ type: 'record', name: 'X', namespace: 'ns', fields: [] })
    await gateway.getEventTypeSchema('tok', 'com.citypass.movilidad.BiciDevuelta.v2')
    expect(fn.mock.calls[0][0]).toBe('/api/v1/event-types/com.citypass.movilidad.BiciDevuelta.v2')
  })

  it('getMetadataSchema pide /event-metadata', async () => {
    const fn = stubFetch({ type: 'record', name: 'EventMetadata', namespace: 'com.citypass.gateway', fields: [] })
    await gateway.getMetadataSchema('tok')
    expect(fn.mock.calls[0][0]).toBe('/api/v1/event-metadata')
  })

  it('updateEventType hace PUT con la lista completa de campos', async () => {
    const fn = stubFetch({ fqn: 'x', topic: 'x', version: 1, schemaId: 1, breaking: false, unchanged: false, previousTopic: null, subscriptionsOnPreviousVersion: null })
    await gateway.updateEventType('tok', 'a.B', [{ name: 'x', type: 'string' }])
    const [url, opts] = fn.mock.calls[0]
    expect(url).toBe('/api/v1/event-types/a.B')
    expect(opts.method).toBe('PUT')
    expect(JSON.parse(opts.body)).toEqual({ fields: [{ name: 'x', type: 'string' }] })
  })

  it('deleteEventType hace DELETE', async () => {
    const fn = stubFetch({ fqn: 'x', deletedTopics: [], subscriptionsRemoved: 0 })
    await gateway.deleteEventType('tok', 'a.B')
    expect(fn.mock.calls[0][1].method).toBe('DELETE')
  })

  it('deleteEventTypeVersion apunta a /versions/{n}', async () => {
    const fn = stubFetch({ fqn: 'x', deletedTopics: [], subscriptionsRemoved: 0 })
    await gateway.deleteEventTypeVersion('tok', 'a.B', 2)
    expect(fn.mock.calls[0][0]).toBe('/api/v1/event-types/a.B/versions/2')
  })

  it('createEventType hace POST con name y fields', async () => {
    const fn = stubFetch({ fqn: 'a.B', namespace: 'a', name: 'B', topic: 'a.B', version: 1, schemaId: 1 })
    await gateway.createEventType('tok', { name: 'B', fields: [{ name: 'x', type: 'int' }] })
    const [url, opts] = fn.mock.calls[0]
    expect(url).toBe('/api/v1/event-types')
    expect(opts.method).toBe('POST')
  })

  it('publishEvent manda el payload de negocio directo, sin envelope', async () => {
    const fn = stubFetch({ metadata: {}, data: {} }, 202)
    await gateway.publishEvent('tok', 'a.B', { x: 1 })
    const [url, opts] = fn.mock.calls[0]
    expect(url).toBe('/api/v1/event-types/a.B/events')
    expect(JSON.parse(opts.body)).toEqual({ x: 1 })
  })

  it('health no manda token', async () => {
    const fn = stubFetch({ status: 'UP', service: 'event-gateway' })
    await gateway.health()
    expect(fn.mock.calls[0][0]).toBe('/health')
    expect((fn.mock.calls[0][1]?.headers as Headers | undefined)?.get('Authorization') ?? null).toBeNull()
  })
})
