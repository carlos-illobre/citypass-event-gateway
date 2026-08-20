#!/usr/bin/env node
/**
 * Levanta el stack de Docker (sin rebuild) y despues el tablero en modo desarrollo.
 *
 * Uso: npm run dashboard
 */

import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const dashboardDir = path.join(root, 'frontend', 'dashboard')

function run(command, args, cwd) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, stdio: 'inherit', shell: true })
    child.on('exit', code => (code === 0 ? resolve() : reject(new Error(`${command} salió con código ${code}`))))
    child.on('error', reject)
  })
}

async function main() {
  console.log('▸ Levantando el stack de Docker (docker compose up -d)…\n')
  await run('docker', ['compose', 'up', '-d'], root)

  // El contenedor `dashboard` del compose publica el build de producción en el mismo puerto
  // 5175 que usa Vite en modo desarrollo. Como acá vamos a levantar el de desarrollo, se detiene
  // el contenedor para liberar el puerto.
  console.log('\n▸ Deteniendo el contenedor `dashboard` (usamos el de desarrollo en su lugar)…\n')
  await run('docker', ['compose', 'stop', 'dashboard'], root)

  console.log('\n▸ Arrancando el tablero en modo desarrollo (http://localhost:5175)…\n')
  await run('npm', ['run', 'dev'], dashboardDir)
}

main().catch(err => {
  console.error(`\n✗ ${err.message}`)
  process.exit(1)
})
