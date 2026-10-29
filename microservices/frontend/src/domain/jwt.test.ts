import { describe, it, expect } from 'vitest'
import { decodeJwt, displayName, expiresAtMs, isExpired, refreshInMs } from './jwt'

function tokenWith(claims: Record<string, unknown>): string {
  const b64url = (s: string) => btoa(s).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '')
  const header = b64url(JSON.stringify({ alg: 'RS256', kid: 'citypass-auth-key' }))
  const payload = b64url(JSON.stringify(claims))
  return `${header}.${payload}.firma-invalida-no-se-verifica-en-el-cliente`
}

describe('decodeJwt', () => {
  it('lee las claims del payload', () => {
    const claims = decodeJwt(tokenWith({ sub: 'grupo1', namespace: 'com.citypass.bus', exp: 1999999999 }))
    expect(claims).toMatchObject({ sub: 'grupo1', namespace: 'com.citypass.bus' })
  })

  it('devuelve un objeto vacío ante un token malformado', () => {
    expect(decodeJwt('esto-no-es-un-jwt')).toEqual({})
    expect(decodeJwt('')).toEqual({})
    expect(decodeJwt('a.b')).toEqual({})
  })

  it('no rompe con un payload que no es JSON válido', () => {
    expect(decodeJwt('a.eyJub3Rqc29u.c')).toEqual({})
  })
})

describe('expiresAtMs', () => {
  it('convierte exp de segundos a milisegundos', () => {
    expect(expiresAtMs({ exp: 1_700_000_000 })).toBe(1_700_000_000_000)
  })

  it('devuelve null sin exp', () => {
    expect(expiresAtMs({})).toBeNull()
  })
})

describe('isExpired', () => {
  it('es true cuando exp ya pasó', () => {
    expect(isExpired({ exp: 1_700_000_000 }, 1_700_000_000_001)).toBe(true)
  })

  it('es false cuando exp todavía no llegó', () => {
    expect(isExpired({ exp: 1_700_000_000 }, 1_699_999_999_000)).toBe(false)
  })

  it('es false sin exp: no hay forma de saber que venció', () => {
    expect(isExpired({}, Date.now())).toBe(false)
  })
})

describe('refreshInMs', () => {
  const exp = 1_700_000_900

  it('renueva un minuto antes del vencimiento', () => {
    expect(refreshInMs({ exp }, 1_700_000_000_000)).toBe(840_000)
  })

  it('acepta otro margen', () => {
    expect(refreshInMs({ exp }, 1_700_000_000_000, 0)).toBe(900_000)
  })

  it('es 0 dentro del margen o ya vencido: se renueva enseguida', () => {
    expect(refreshInMs({ exp }, 1_700_000_870_000)).toBe(0)
    expect(refreshInMs({ exp }, 1_700_001_000_000)).toBe(0)
  })

  it('es null sin exp', () => {
    expect(refreshInMs({}, Date.now())).toBeNull()
  })
})

describe('displayName', () => {
  it('prefiere el uid de LDAP del token humano', () => {
    expect(displayName({ sub: 'U000008', preferred_username: 'delegado-eda' })).toBe('delegado-eda')
  })

  it('cae al sub en un token de servicio', () => {
    expect(displayName({ sub: 'grupo1' })).toBe('grupo1')
  })

  it('es vacío sin ninguno de los dos', () => {
    expect(displayName({})).toBe('')
  })
})
