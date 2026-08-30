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
