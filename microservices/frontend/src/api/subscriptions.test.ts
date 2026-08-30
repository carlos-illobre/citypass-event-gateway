import { describe, it, expect, vi, afterEach } from 'vitest'
import { subscriptions } from './subscriptions'

function stubFetch(body: unknown, status = 200) {
  const fn = vi.fn().mockResolvedValue({
    ok: status < 400, status, statusText: 'OK', headers: new Headers(),
    text: () => Promise.resolve(body === undefined ? '' : JSON.stringify(body)),
    json: () => Promise.resolve(body),
  } as Response)
  vi.stubGlobal('fetch', fn)
  return fn
}

afterEach(() => vi.unstubAllGlobals())

describe('subscriptions', () => {
  it('list sin filtro pide el listado entero del namespace del token', async () => {
    const fn = stubFetch([])
    await subscriptions.list('tok')
    expect(fn.mock.calls[0][0]).toBe('/api/v1/subscriptions')
  })

  it('list con topic lo codifica en la query', async () => {
    const fn = stubFetch([])
    await subscriptions.list('tok', 'com.citypass.movilidad.BiciDevuelta')
    expect(fn.mock.calls[0][0]).toBe('/api/v1/subscriptions?topic=com.citypass.movilidad.BiciDevuelta')
  })

  it('create hace POST con topic y callbackUrl', async () => {
    const fn = stubFetch({ id: '1', topic: 't', callbackUrl: 'https://x', owner: 'ns', createdBy: 'sub', createdAt: '' })
    await subscriptions.create('tok', { topic: 't', callbackUrl: 'https://x' })
    const [url, opts] = fn.mock.calls[0]
    expect(url).toBe('/api/v1/subscriptions')
    expect(opts.method).toBe('POST')
    expect(JSON.parse(opts.body)).toEqual({ topic: 't', callbackUrl: 'https://x' })
  })

  it('remove hace DELETE por id, y no espera cuerpo (204)', async () => {
    const fn = stubFetch(undefined, 204)
    await subscriptions.remove('tok', 'abc-123')
    const [url, opts] = fn.mock.calls[0]
    expect(url).toBe('/api/v1/subscriptions/abc-123')
    expect(opts.method).toBe('DELETE')
  })
})
