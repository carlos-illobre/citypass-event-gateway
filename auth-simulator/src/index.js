/**
 * Simulador del servicio de identidad de CityPass+.
 *
 * Este archivo es además la ESPECIFICACIÓN de lo que el servicio real debe implementar.
 * Todo lo que está acá es obligatorio; lo único simulado es de dónde salen los clientes
 * y cómo se guarda la clave (ver «Qué debe cambiar en el servicio real», más abajo).
 *
 * ── Contrato ────────────────────────────────────────────────────────────────
 *
 * POST /oauth/token
 *   Flujo `client_credentials` de OAuth2 (RFC 6749). Credenciales por HTTP Basic o
 *   en el cuerpo del formulario.
 *   → { "access_token": "<JWT>", "token_type": "Bearer", "expires_in": <segundos> }
 *   Errores con la forma del RFC: { "error": "...", "error_description": "..." }
 *
 * GET /.well-known/jwks.json
 *   Claves públicas en formato JWKS. El event-gateway y el broker Kafka validan
 *   las firmas contra este endpoint, así que es lo que hace que la identidad sea
 *   una sola para toda la plataforma.
 *
 * ── Claims obligatorios del JWT ─────────────────────────────────────────────
 *
 *   sub        Usuario individual que pidió el token. El gateway lo guarda en
 *              `metadata.source` de cada evento: es la traza de quién publicó.
 *   namespace  Identificador del grupo. Cumple dos funciones: es la identidad con la
 *              que Kafka autoriza el consumo, y el prefijo de los tópicos que el
 *              grupo posee. Un grupo sólo puede publicar en `<namespace>.*`.
 *   aud        Audiencia. El broker la verifica.
 *   jti        Identificador único de esta emisión. El gateway lo guarda en
 *              `metadata.tokenId`, para acotar el impacto de una credencial filtrada.
 *   iat, exp   Emisión y vencimiento.
 *
 * ── Qué debe cambiar en el servicio real ────────────────────────────────────
 *
 *   1. Los clientes salen de una base de datos, no de un objeto en el código.
 *   2. Los secrets se guardan hasheados, nunca en texto plano.
 *   3. La clave de firma persiste entre reinicios. Acá se genera en memoria, así que
 *      cada arranque invalida todos los tokens emitidos.
 *   4. El JWKS debe poder exponer varias claves a la vez, cada una con su `kid`, para
 *      poder rotar sin cortarle el acceso a nadie.
 *   5. `expires_in` debería ser corto (5 a 15 minutos). Los clientes renuevan solos, y
 *      un token corto es lo que permite que revocar un cliente tenga efecto rápido:
 *      no hay ninguna otra lista de la que haya que borrarlo.
 */

const express = require('express')
const { generateKeyPair, SignJWT, exportJWK } = require('jose')
const { randomUUID } = require('node:crypto')
const cors = require('cors')

const app = express()
app.use(express.json())
// OAuth2 envía las credenciales como formulario, no como JSON.
app.use(express.urlencoded({ extended: false }))

if (!process.env.AUTH_CORS_ORIGIN) throw new Error('AUTH_CORS_ORIGIN no está configurado')

app.use(cors({
  origin: process.env.AUTH_CORS_ORIGIN.split(','),
  methods: ['GET', 'POST'],
  allowedHeaders: ['Content-Type', 'Authorization'],
}))

/**
 * Clientes de la plataforma.
 *
 * La identidad es el grupo, no una persona: el mismo grupo se autentica desde la UI y
 * desde sus servicios. Cada uno tiene su namespace, que lo identifica y delimita los
 * tópicos que le pertenecen.
 *
 * No hay clientes privilegiados a propósito: un namespace comodín sería una llave
 * maestra sobre todos los tópicos, y una sola credencial filtrada comprometería el bus
 * entero. Todos los grupos tienen exactamente los mismos permisos sobre lo suyo.
 */
const CLIENTS = {
  grupo2: { secret: 'grupo2', namespace: 'com.citypass.auth' },
  grupo3: { secret: 'grupo3', namespace: 'com.citypass.movilidad' },
  grupo4: { secret: 'grupo4', namespace: 'com.citypass.reclamos' },
  grupo5: { secret: 'grupo5', namespace: 'com.citypass.emergencias' },
  grupo6: { secret: 'grupo6', namespace: 'com.citypass.turismo' },
  grupo7: { secret: 'grupo7', namespace: 'com.citypass.transporte' },
  grupo8: { secret: 'grupo8', namespace: 'com.citypass.analitica' },
}

/** Segundos de vigencia del token. OAuth2 exige `expires_in` numérico. */
const TOKEN_TTL_SECONDS = 8 * 60 * 60

/** Audiencia del token: para quién fue emitido. El broker Kafka la verifica. */
const AUDIENCE = 'citypass'

let privateKey, publicJwk

async function init() {
  const { privateKey: priv, publicKey: pub } = await generateKeyPair('RS256')
  privateKey = priv
  publicJwk = { ...(await exportJWK(pub)), use: 'sig', alg: 'RS256', kid: 'citypass-auth-key' }
  console.log('Auth simulator listo — par de claves RS256 generado')
}

/** Lee las credenciales del header Basic o, si no está, del cuerpo del formulario. */
function readCredentials(req) {
  const header = req.get('authorization') || ''
  if (header.startsWith('Basic ')) {
    const [clientId, clientSecret] = Buffer.from(header.slice(6), 'base64').toString().split(':')
    return { clientId, clientSecret }
  }
  const { client_id: clientId, client_secret: clientSecret } = req.body || {}
  return { clientId, clientSecret }
}

app.post('/oauth/token', async (req, res) => {
  const fail = (status, error, description) =>
    res.status(status).json({ error, error_description: description })

  if ((req.body || {}).grant_type !== 'client_credentials')
    return fail(400, 'unsupported_grant_type', "El único grant soportado es 'client_credentials'.")

  const { clientId, clientSecret } = readCredentials(req)
  if (!clientId || !clientSecret)
    return fail(400, 'invalid_request', 'Faltan client_id y client_secret.')

  const client = CLIENTS[clientId]
  if (!client || client.secret !== clientSecret)
    return fail(401, 'invalid_client', 'Las credenciales no son válidas.')

  const accessToken = await new SignJWT({ namespace: client.namespace })
    .setProtectedHeader({ alg: 'RS256', kid: publicJwk.kid })
    .setSubject(clientId)
    .setAudience(AUDIENCE)
    .setJti(randomUUID())
    .setIssuedAt()
    .setExpirationTime(`${TOKEN_TTL_SECONDS}s`)
    .sign(privateKey)

  console.log(`Token emitido: ${clientId} (${client.namespace})`)
  res.json({ access_token: accessToken, token_type: 'Bearer', expires_in: TOKEN_TTL_SECONDS })
})

app.get('/.well-known/jwks.json', (_req, res) => {
  res.json({ keys: [publicJwk] })
})

app.get('/health', (_req, res) => {
  res.json({ status: 'UP', service: 'auth-simulator' })
})

const PORT = process.env.PORT || 8083
init().then(() => {
  app.listen(PORT, () => {
    console.log(`Auth simulator escuchando en puerto ${PORT}`)
    console.log(`Clientes: ${Object.keys(CLIENTS).join(', ')}`)
    console.log(`JWKS: http://localhost:${PORT}/.well-known/jwks.json`)
  })
})
