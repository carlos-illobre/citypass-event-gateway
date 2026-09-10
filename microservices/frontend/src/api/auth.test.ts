import { describe, it, expect, vi, afterEach } from 'vitest'
import { auth } from './auth'

afterEach(() => vi.unstubAllGlobals())

describe('auth.login', () => {
  it('manda client_credentials con usuario/contraseña como client_id/client_secret', async () => {
    const fn = vi.fn().mockResolvedValue({
      ok: true, status: 200, headers: new Headers(),
      text: () => Promise.resolve(JSON.stringify({ access_token: 'jwt.abc', token_type: 'Bearer', expires_in: 28800 })),
      json: () => Promise.resolve({ access_token: 'jwt.abc', token_type: 'Bearer', expires_in: 28800 }),
    } as Response)
    vi.stubGlobal('fetch', fn)

    const { token } = await auth.login({ username: 'grupo1', password: 'grupo1' })

    expect(token).toBe('jwt.abc')
    const [url, opts] = fn.mock.calls[0]
    expect(url).toBe('/auth/oauth/token')
    expect(opts.method).toBe('POST')
    expect((opts.headers as Headers).get('Content-Type')).toBe('application/x-www-form-urlencoded')
    const body = new URLSearchParams(opts.body)
    expect(body.get('grant_type')).toBe('client_credentials')
    expect(body.get('client_id')).toBe('grupo1')
    expect(body.get('client_secret')).toBe('grupo1')
  })

  it('credenciales inválidas rechazan con el mensaje del simulador', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 401, statusText: 'Unauthorized', headers: new Headers(),
      text: () => Promise.resolve(JSON.stringify({ error: 'invalid_client', error_description: 'Credenciales inválidas' })),
    } as Response))
    await expect(auth.login({ username: 'x', password: 'y' })).rejects.toThrow('Credenciales inválidas')
  })
})

describe('auth.health', () => {
  it('pide /auth/health', async () => {
    const fn = vi.fn().mockResolvedValue({
      ok: true, status: 200, headers: new Headers(),
      text: () => Promise.resolve(JSON.stringify({ status: 'UP', service: 'auth-simulator' })),
      json: () => Promise.resolve({ status: 'UP', service: 'auth-simulator' }),
    } as Response)
    vi.stubGlobal('fetch', fn)
    await auth.health()
    expect(fn.mock.calls[0][0]).toBe('/auth/health')
  })
})
