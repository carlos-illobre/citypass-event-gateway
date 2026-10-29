// Decodificación del JWT. Pura: no valida la firma —eso ya lo hizo el gateway— sólo lee las
// claims para mostrarlas. No importa React ni la API.

export type Claims = {
  sub?:       string
  namespace?: string
  aud?:       string | string[]
  jti?:       string
  iat?:       number
  exp?:       number
  /** Sólo en el token humano: el uid de LDAP, legible (el `sub` es el employeeNumber). */
  preferred_username?: string
  /** Sólo en el token humano: la OU de la persona, de donde sale su `namespace`. */
  module?:    string
}

/** Decodifica el payload de un JWT. Devuelve `{}` ante cualquier token malformado. */
export function decodeJwt(token: string): Claims {
  try {
    const [, payload] = token.split('.')
    if (!payload) return {}
    const base64 = payload.replaceAll('-', '+').replaceAll('_', '/')
    const json = new TextDecoder().decode(
      Uint8Array.from(atob(base64), c => c.charCodeAt(0)),
    )
    return JSON.parse(json) as Claims
  } catch {
    return {}
  }
}

/** `exp` viaja en segundos; todo lo demás en la app trabaja en milisegundos. */
export const expiresAtMs = (claims: Claims): number | null =>
  typeof claims.exp === 'number' ? claims.exp * 1000 : null

export const isExpired = (claims: Claims, now: number): boolean => {
  const exp = expiresAtMs(claims)
  return exp !== null && exp <= now
}

/** Cuánto antes del vencimiento se renueva: deja margen para una pestaña en segundo plano. */
export const REFRESH_MARGIN_MS = 60_000

/**
 * En cuántos milisegundos hay que renovar el access token. `0` si ya está dentro del
 * margen (o vencido: el refresh token dura más, así que todavía se puede renovar); `null`
 * sin `exp`, porque entonces no hay vencimiento que anticipar.
 */
export const refreshInMs = (claims: Claims, now: number, marginMs = REFRESH_MARGIN_MS): number | null => {
  const exp = expiresAtMs(claims)
  return exp === null ? null : Math.max(0, exp - marginMs - now)
}

/** El nombre a mostrar: el uid de LDAP si es un token humano, el `sub` si es de servicio. */
export const displayName = (claims: Claims): string =>
  claims.preferred_username ?? claims.sub ?? ''
