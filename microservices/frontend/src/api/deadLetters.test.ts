import { describe, it, expect, vi, afterEach } from 'vitest'
import { deadLetters } from './deadLetters'

afterEach(() => vi.unstubAllGlobals())

describe('deadLetters.list', () => {
  it('arma la URL con el límite pedido', async () => {
    const fn = vi.fn().mockResolvedValue({
      ok: true, status: 200, headers: new Headers(),
      text: () => Promise.resolve('{"topic":"sistema.dlq","returned":0,"messages":[]}'),
      json: () => Promise.resolve({ topic: 'sistema.dlq', returned: 0, messages: [] }),
    } as Response)
    vi.stubGlobal('fetch', fn)

    const result = await deadLetters.list('tok', 100)

    expect(fn.mock.calls[0][0]).toBe('/api/v1/dead-letters?limit=100')
    expect(result.topic).toBe('sistema.dlq')
  })

  it('usa 50 por defecto', async () => {
    const fn = vi.fn().mockResolvedValue({
      ok: true, status: 200, headers: new Headers(),
      text: () => Promise.resolve('{}'), json: () => Promise.resolve({}),
    } as Response)
    vi.stubGlobal('fetch', fn)
    await deadLetters.list('tok')
    expect(fn.mock.calls[0][0]).toBe('/api/v1/dead-letters?limit=50')
  })
})

describe('deadLetters.retry', () => {
  it('hace POST a /reintentar con el id escapado', async () => {
    const body = { dlqId: 'a/b', estado: 'entregado', callbackUrl: 'https://x.test/hook' }
    const fn = vi.fn().mockResolvedValue({
      ok: true, status: 200, headers: new Headers(),
      text: () => Promise.resolve(JSON.stringify(body)), json: () => Promise.resolve(body),
    } as Response)
    vi.stubGlobal('fetch', fn)

    const result = await deadLetters.retry('tok', 'a/b')

    expect(fn.mock.calls[0][0]).toBe('/api/v1/dead-letters/a%2Fb/reintentar')
    expect(fn.mock.calls[0][1].method).toBe('POST')
    expect(result.estado).toBe('entregado')
  })

  it('un 502 llega como error con el detalle en el cuerpo', async () => {
    const body = { dlqId: 'x', estado: 'fallido', callbackUrl: 'https://x.test/hook', detalle: 'El destino volvió a fallar.' }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 502, statusText: 'Bad Gateway', headers: new Headers(),
      text: () => Promise.resolve(JSON.stringify(body)),
    } as Response))

    await expect(deadLetters.retry('tok', 'x')).rejects.toMatchObject({ status: 502, problem: { detalle: 'El destino volvió a fallar.' } })
  })
})
