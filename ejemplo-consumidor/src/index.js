import express from 'express';
import { dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const app = express();
const PORT = process.env.PORT || 3001;
const GATEWAY_URL = process.env.EVENT_GATEWAY_URL || 'http://localhost:8080';
const SELF_URL = process.env.SELF_URL || `http://localhost:${PORT}`;

app.use(express.json());
app.use(express.urlencoded({ extended: false }));
app.use(express.static(__dirname));

const events = [];

app.get('/api/schemas', async (req, res) => {
  try {
    const r = await fetch(`${GATEWAY_URL}/api/v1/schemas`);
    res.json(await r.json());
  } catch { res.json({ eventTypes: [] }); }
});

app.get('/api/events', (req, res) => res.json([...events].reverse()));

app.post('/subscribe', async (req, res) => {
  const { topic } = req.body;
  await fetch(`${GATEWAY_URL}/api/v1/subscriptions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ topic, callbackUrl: `${SELF_URL}/webhook` }),
  });
  res.redirect('/');
});

app.post('/webhook', (req, res) => {
  events.push({
    receivedAt: new Date().toISOString(),
    topic: req.body.eventType ?? '?',
    payload: req.body,
  });
  if (events.length > 200) events.splice(0, events.length - 200);
  res.sendStatus(200);
});

app.listen(PORT, () => console.log(`ejemplo-consumidor en http://localhost:${PORT}`));
