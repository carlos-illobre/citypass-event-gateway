import { describe, it, expect, vi, afterEach } from 'vitest'
import { auth } from './auth'

afterEach(() => vi.unstubAllGlobals())

const ok = (body: unknown, status = 200) => vi.fn().mockResolvedValue({
  ok: true, status, headers: new Headers(),
  text: () => Promise.resolve(JSON.stringify(body)),
  json: () => Promise.resolve(body),
} as Response)

const par = { access_token: 'jwt.abc', refresh_token: 'rt.xyz', token_type: 'Bearer', expires_in: 900 }

describe('auth.login', () => {
  it('manda usuario, contraseña y el cliente de la consola, y devuelve los dos tokens', async () => {
    const fn = ok(par)
    vi.stubGlobal('fetch', fn)

    const session = await auth.login({ username: 'delegado-eda', password: 'secreta' })

    expect(session).toEqual({ accessToken: 'jwt.abc', refreshToken: 'rt.xyz' })
    const [url, opts] = fn.mock.calls[0]
    expect(url).toBe('/auth/auth/login')
    expect(opts.method).toBe('POST')
    expect((opts.headers as Headers).get('Content-Type')).toBe('application/json')
    expect(JSON.parse(opts.body)).toEqual({
      username: 'delegado-eda', password: 'secreta', clientId: 'citypass-bus-web',
    })
  })

  it('credenciales inválidas rechazan con el mensaje del servicio de identidad', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 401, statusText: 'Unauthorized', headers: new Headers(),
      text: () => Promise.resolve(JSON.stringify({
        status: 401, error: 'Credenciales inválidas', message: 'Usuario o contraseña inválidos',
      })),
    } as Response))
    await expect(auth.login({ username: 'x', password: 'y' })).rejects.toThrow('Credenciales inválidas')
  })
})

describe('auth.refresh', () => {
  it('canjea el refresh token por un par nuevo', async () => {
    const fn = ok({ ...par, access_token: 'jwt.nuevo', refresh_token: 'rt.nuevo' })
    vi.stubGlobal('fetch', fn)

    const session = await auth.refresh('rt.xyz')

    expect(session).toEqual({ accessToken: 'jwt.nuevo', refreshToken: 'rt.nuevo' })
    const [url, opts] = fn.mock.calls[0]
    expect(url).toBe('/auth/auth/refresh')
    expect(JSON.parse(opts.body)).toEqual({ refreshToken: 'rt.xyz' })
  })
})

describe('auth.logout', () => {
  it('revoca el refresh token', async () => {
    const fn = vi.fn().mockResolvedValue({ ok: true, status: 204, headers: new Headers() } as Response)
    vi.stubGlobal('fetch', fn)

    await auth.logout('rt.xyz')

    const [url, opts] = fn.mock.calls[0]
    expect(url).toBe('/auth/auth/logout')
    expect(opts.method).toBe('POST')
    expect(JSON.parse(opts.body)).toEqual({ refreshToken: 'rt.xyz' })
  })
})

describe('auth.health', () => {
  it('pide /auth/health', async () => {
    const fn = ok({ status: 'UP' })
    vi.stubGlobal('fetch', fn)
    await auth.health()
    expect(fn.mock.calls[0][0]).toBe('/auth/health')
  })
})
