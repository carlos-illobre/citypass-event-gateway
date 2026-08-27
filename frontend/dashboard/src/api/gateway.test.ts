import { describe, it, expect, vi, afterEach } from 'vitest'
import { gateway } from './gateway'
import { anomaly } from './anomalies'
import { auth } from './auth'

const spy = () => {
  const fn = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(
    () => Promise.resolve(new Response('{}', { headers: { 'Content-Type': 'application/json' } }))
  )
  vi.stubGlobal('fetch', fn)
  return fn
}

const urlOf = (fn: ReturnType<typeof spy>) => fn.mock.calls[0][0]
const initOf = (fn: ReturnType<typeof spy>) => fn.mock.calls[0][1] as RequestInit

afterEach(() => vi.unstubAllGlobals())

describe('gateway', () => {
  it('pide el catálogo completo cuando no se acota el namespace', async () => {
    const fn = spy()
    await gateway.listEventTypes('t')
    // Sin `?namespace=`: el listado global es el comportamiento por defecto a propósito.
    expect(urlOf(fn)).toBe('/api/v1/event-types')
  })

  it('acota por namespace cuando se lo piden, escapando el valor', async () => {
    const fn = spy()
    await gateway.listEventTypes('t', 'com.citypass/raro')
    expect(urlOf(fn)).toBe('/api/v1/event-types?namespace=com.citypass%2Fraro')
  })

  it('escapa el FQN en la ruta del esquema', async () => {
    // El FQN va en la ruta, y aunque hoy sólo trae puntos, no escaparlo sería un bug esperando
    // el primer nombre con un carácter reservado.
    const fn = spy()
    await gateway.getEventTypeSchema('t', 'com.citypass.movilidad.Bici Devuelta')
    expect(urlOf(fn)).toBe('/api/v1/event-types/com.citypass.movilidad.Bici%20Devuelta')
  })

  it('manda el token como Bearer en las consultas autenticadas', async () => {
    const fn = spy()
    await gateway.listEventTypes('mi-token')
    expect((initOf(fn).headers as Headers).get('Authorization')).toBe('Bearer mi-token')
  })

  it('usa el límite por defecto y respeta el que le pasen', async () => {
    const fn1 = spy()
    await gateway.listMyEvents('t')
    expect(urlOf(fn1)).toBe('/api/v1/events?limit=50')

    const fn2 = spy()
    await gateway.listMyEvents('t', 200)
    expect(urlOf(fn2)).toBe('/api/v1/events?limit=200')
  })

  it('arma las URLs de la cola de fallidos y de las suscripciones', async () => {
    const fn1 = spy()
    await gateway.listDeadLetters('t')
    expect(urlOf(fn1)).toBe('/api/v1/dead-letters?limit=100')

    const fn2 = spy()
    await gateway.listSubscriptions('t')
    expect(urlOf(fn2)).toBe('/api/v1/subscriptions')
  })

  it('consulta la salud sin token: es el único endpoint público del gateway', async () => {
    const fn = spy()
    await gateway.health()
    expect(urlOf(fn)).toBe('/health')
    expect((initOf(fn).headers as Headers).has('Authorization')).toBe(false)
  })

  it('pide el esquema del sobre', async () => {
    const fn = spy()
    await gateway.getMetadataSchema('t')
    expect(urlOf(fn)).toBe('/api/v1/event-metadata')
  })
})

describe('anomaly', () => {
  it('no manda token: el detector no lo pide', async () => {
    const fn = spy()
    await anomaly.list()
    expect((initOf(fn).headers as Headers).has('Authorization')).toBe(false)
  })

  it('va por el prefijo del proxy y conserva su propio /api/v1', async () => {
    // El prefijo `/anomaly` es lo que mantiene separados los dos `/api/v1` —el del gateway y el
    // del detector— desde el punto de vista del navegador.
    const fn = spy()
    await anomaly.list(25)
    expect(urlOf(fn)).toBe('/anomaly/api/v1/anomalies?limit=25')
  })

  it('usa el límite por defecto', async () => {
    const fn = spy()
    await anomaly.list()
    expect(urlOf(fn)).toBe('/anomaly/api/v1/anomalies?limit=100')
  })

  it('arma las URLs de estado y salud', async () => {
    const fn1 = spy()
    await anomaly.modelStatus()
    expect(urlOf(fn1)).toBe('/anomaly/api/v1/model/status')

    const fn2 = spy()
    await anomaly.health()
    expect(urlOf(fn2)).toBe('/anomaly/health')
  })
})

describe('auth', () => {
  it('pide el token como formulario, con grant_type client_credentials', async () => {
    const fn = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(
      () => Promise.resolve(new Response(JSON.stringify({ access_token: 'abc' }), {
        headers: { 'Content-Type': 'application/json' },
      }))
    )
    vi.stubGlobal('fetch', fn)

    const resultado = await auth.login({ username: 'grupo8', password: 'grupo8' })

    expect(fn.mock.calls[0][0]).toBe('/auth/oauth/token')
    const init = fn.mock.calls[0][1] as RequestInit
    expect(init.method).toBe('POST')
    expect((init.headers as Headers).get('Content-Type')).toBe('application/x-www-form-urlencoded')
    expect(init.body).toBe('grant_type=client_credentials&client_id=grupo8&client_secret=grupo8')
    // El `access_token` de OAuth2 se renombra a `token` acá y no en cada pantalla.
    expect(resultado).toEqual({ token: 'abc' })
  })
})
