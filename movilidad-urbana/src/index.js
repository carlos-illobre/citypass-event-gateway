const express = require('express');
const app = express();
app.use(express.json());

const REST_PROXY_URL = process.env.REST_PROXY_URL || 'http://rest-proxy:8080';
const PORT = process.env.PORT || 3000;

const ESTACIONES = [
  { id: 'est-001', nombre: 'Estacion Obelisco' },
  { id: 'est-002', nombre: 'Estacion Retiro' },
  { id: 'est-003', nombre: 'Estacion Congreso' },
  { id: 'est-004', nombre: 'Estacion Puerto Madero' },
  { id: 'est-005', nombre: 'Estacion Palermo' },
];

function randomBiciDevuelta() {
  const estacion = ESTACIONES[Math.floor(Math.random() * ESTACIONES.length)];
  return {
    eventType: 'movilidad.bici.devuelta',
    source: 'grupo3-movilidad',
    data: {
      userId: `user-${Math.floor(Math.random() * 1000)}`,
      biciId: `bici-${Math.floor(Math.random() * 500)}`,
      estacionDevolucionId: estacion.id,
      estacionDevolucionNombre: estacion.nombre,
      duracionMinutos: Math.floor(Math.random() * 120) + 5,
      distanciaKm: Math.round((Math.random() * 20 + 0.5) * 10) / 10,
    },
  };
}

app.post('/api/simulate/bici-devuelta', async (req, res) => {
  try {
    const event = randomBiciDevuelta();
    console.log('Sending event to REST proxy:', JSON.stringify(event, null, 2));

    const response = await fetch(`${REST_PROXY_URL}/api/v1/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(event),
    });

    const result = await response.json();
    console.log('REST proxy response:', JSON.stringify(result, null, 2));

    res.json({ simulatedEvent: event, proxyResponse: result });
  } catch (error) {
    console.error('Error sending event:', error.message);
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/simulate/burst', async (req, res) => {
  const count = req.body.count || 5;
  const results = [];

  for (let i = 0; i < count; i++) {
    const event = randomBiciDevuelta();
    try {
      const response = await fetch(`${REST_PROXY_URL}/api/v1/events`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(event),
      });
      results.push(await response.json());
    } catch (error) {
      results.push({ error: error.message });
    }
  }

  res.json({ sent: count, results });
});

app.get('/health', (req, res) => {
  res.json({ status: 'UP', service: 'group3-simulator' });
});

app.listen(PORT, () => {
  console.log(`=== Group 3 Simulator (Movilidad Urbana) ===`);
  console.log(`Server running on port ${PORT}`);
  console.log(`REST Proxy URL: ${REST_PROXY_URL}`);
  console.log(`Endpoints:`);
  console.log(`  POST /api/simulate/bici-devuelta  - Simulate one bike return event`);
  console.log(`  POST /api/simulate/burst          - Simulate multiple events (body: {"count": N})`);
  console.log(`  GET  /health                      - Health check`);
});
