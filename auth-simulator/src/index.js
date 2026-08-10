const express = require('express')
const { generateKeyPair, SignJWT, exportJWK } = require('jose')

const app = express()
app.use(express.json())

// Un usuario pre-configurado por grupo.
// El Grupo 2 reemplazará esto con su propio sistema de usuarios/LDAP.
const USERS = {
  admin:   { password: 'admin',    grupo: 'grupo1', role: 'admin',     allowedTopics: ['*'] },
  grupo2:  { password: 'grupo2',   grupo: 'grupo2', role: 'publisher', allowedTopics: ['auth.*'] },
  grupo3:  { password: 'grupo3',   grupo: 'grupo3', role: 'publisher', allowedTopics: ['movilidad.*'] },
  grupo4:  { password: 'grupo4',   grupo: 'grupo4', role: 'publisher', allowedTopics: ['reclamos.*'] },
  grupo5:  { password: 'grupo5',   grupo: 'grupo5', role: 'publisher', allowedTopics: ['emergencias.*'] },
  grupo6:  { password: 'grupo6',   grupo: 'grupo6', role: 'publisher', allowedTopics: ['turismo.*'] },
  grupo7:  { password: 'grupo7',   grupo: 'grupo7', role: 'publisher', allowedTopics: ['transporte.*'] },
  grupo8:  { password: 'grupo8',   grupo: 'grupo8', role: 'consumer',  allowedTopics: [] },
}

let privateKey, publicJwk

async function init() {
  const { privateKey: priv, publicKey: pub } = await generateKeyPair('RS256')
  privateKey = priv
  publicJwk = { ...(await exportJWK(pub)), use: 'sig', alg: 'RS256', kid: 'citypass-auth-key' }
  console.log('Auth simulator listo — par de claves RS256 generado')
}

// POST /auth/login
// Body: { "username": "grupo3", "password": "grupo3" }
// Response: { "token": "...", "expiresIn": "8h", "grupo": "grupo3", "role": "publisher" }
app.post('/auth/login', async (req, res) => {
  const { username, password } = req.body || {}

  if (!username || !password)
    return res.status(400).json({ error: 'Se requieren username y password' })

  const user = USERS[username]
  if (!user || user.password !== password)
    return res.status(401).json({ error: 'Credenciales inválidas' })

  const token = await new SignJWT({
    grupo:          user.grupo,
    role:           user.role,
    allowedTopics:  user.allowedTopics,
  })
    .setProtectedHeader({ alg: 'RS256', kid: 'citypass-auth-key' })
    .setSubject(username)
    .setIssuedAt()
    .setExpirationTime('8h')
    .sign(privateKey)

  console.log(`Login OK: ${username} (${user.grupo}, ${user.role})`)
  res.json({ token, expiresIn: '8h', grupo: user.grupo, role: user.role })
})

// GET /.well-known/jwks.json
// Endpoint estándar OAuth2/OIDC — el REST Proxy lo usa para validar firmas JWT.
// Cuando el Grupo 2 implemente su servicio real, debe exponer esta misma ruta.
app.get('/.well-known/jwks.json', (_req, res) => {
  res.json({ keys: [publicJwk] })
})

// GET /health
app.get('/health', (_req, res) => {
  res.json({ status: 'UP', service: 'auth-simulator' })
})

const PORT = process.env.PORT || 8083
init().then(() => {
  app.listen(PORT, () => {
    console.log(`Auth simulator escuchando en puerto ${PORT}`)
    console.log(`Usuarios disponibles: ${Object.keys(USERS).join(', ')}`)
    console.log(`JWKS: http://localhost:${PORT}/.well-known/jwks.json`)
  })
})
