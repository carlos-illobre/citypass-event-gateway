#!/usr/bin/env node
/**
 * Semillero de datos de demostración.
 *
 * Registra un par de tipos de evento en el namespace de este equipo (`com.citypass.bus`,
 * cliente `grupo1`) y publica un lote, para que la consola no arranque con las ocho
 * pantallas vacías. Corre en Node, no en el navegador: le pega directo al gateway sin
 * pasar por el proxy de Vite, así sirve igual con la consola apagada.
 *
 * Uso:
 *   node scripts/seed-demo.mjs                publica el lote por defecto
 *   node scripts/seed-demo.mjs --eventos 120  publica esa cantidad
 */

const GATEWAY = process.env.SEED_GATEWAY_URL ?? 'http://localhost:8080'
const AUTH    = process.env.SEED_AUTH_URL    ?? 'http://localhost:8083'
const CLIENT  = process.env.SEED_CLIENT_ID     ?? 'grupo1'
const SECRET  = process.env.SEED_CLIENT_SECRET ?? 'grupo1'

// El detector de anomalías necesita 50 muestras para entrenar; por debajo de eso la
// pantalla de anomalías se queda diciendo "acumulando" y la demo queda a mitad de camino.
const EVENTOS_POR_DEFECTO = 60

const TIPOS = [
  {
    name: 'DemoTopicoRegistrado',
    fields: [
      { name: 'fqn',       type: 'string' },
      { name: 'namespace', type: 'string' },
      { name: 'version',   type: 'int' },
    ],
  },
  {
    name: 'DemoWebhookEntregado',
    fields: [
      { name: 'topic',      type: 'string' },
      { name: 'intento',    type: 'int' },
      { name: 'exitoso',    type: 'boolean' },
      { name: 'latenciaMs', type: 'long' },
    ],
  },
]

const espera = ms => new Promise(r => setTimeout(r, ms))
const azar   = lista => lista[Math.floor(Math.random() * lista.length)]
const entero = (min, max) => Math.floor(Math.random() * (max - min + 1)) + min
const id     = prefijo => `${prefijo}-${Math.random().toString(36).slice(2, 8)}`

async function pedirToken() {
  const r = await fetch(`${AUTH}/oauth/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'client_credentials', client_id: CLIENT, client_secret: SECRET }),
  })
  if (!r.ok) throw new Error(`No se pudo obtener token (${r.status}): ${await r.text()}`)
  return (await r.json()).access_token
}

async function registrarTipo(token, tipo) {
  const r = await fetch(`${GATEWAY}/api/v1/event-types`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ name: tipo.name, fields: tipo.fields }),
  })
  if (r.status === 201) { console.log(`  + ${tipo.name} registrado`); return }
  if (r.status === 400) { console.log(`  · ${tipo.name} ya existía`); return }
  throw new Error(`${tipo.name}: ${r.status} ${await r.text()}`)
}

function payloadPara(name) {
  if (name === 'DemoTopicoRegistrado') {
    return { fqn: `com.citypass.bus.${id('Tipo')}`, namespace: 'com.citypass.bus', version: 1 }
  }
  return { topic: `com.citypass.movilidad.${id('Tipo')}`, intento: entero(1, 3), exitoso: Math.random() > 0.15, latenciaMs: entero(20, 900) }
}

async function publicarEventos(token, cantidad) {
  let ok = 0
  for (let i = 0; i < cantidad; i++) {
    const tipo = azar(TIPOS)
    const r = await fetch(`${GATEWAY}/api/v1/event-types/com.citypass.bus.${tipo.name}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(payloadPara(tipo.name)),
    })
    if (r.status === 202) ok++
    else console.error(`  ! evento ${i + 1} rechazado (${r.status}): ${await r.text()}`)
    await espera(30)
  }
  console.log(`  ${ok}/${cantidad} eventos publicados`)
}

async function main() {
  const args = process.argv.slice(2)
  const idx = args.indexOf('--eventos')
  const cantidad = idx >= 0 ? Number(args[idx + 1]) : EVENTOS_POR_DEFECTO

  console.log(`Pidiendo token para ${CLIENT}…`)
  const token = await pedirToken()

  console.log('Registrando tipos de demostración…')
  for (const tipo of TIPOS) await registrarTipo(token, tipo)

  console.log(`Publicando ${cantidad} eventos…`)
  await publicarEventos(token, cantidad)

  console.log('Listo. La consola ya tiene datos para mostrar.')
}

main().catch(err => { console.error(err.message); process.exitCode = 1 })
