import express from 'express';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const app = express();
const PORT = process.env.PORT || 3000;
const GATEWAY_URL = process.env.EVENT_GATEWAY_URL || 'http://localhost:8080';

app.use(express.urlencoded({ extended: false }));
app.use(express.static(__dirname));

app.get('/api/schemas', async (req, res) => {
  try {
    const r = await fetch(`${GATEWAY_URL}/api/v1/schemas`);
    res.json(await r.json());
  } catch { res.json({ eventTypes: [] }); }
});

app.post('/schemas', async (req, res) => {
  const { namespace, name, fields: fieldsText } = req.body;
  let fields;
  try { fields = JSON.parse(fieldsText); }
  catch { return res.redirect('/?msg=err&detail=JSON+inválido+en+fields'); }

  try {
    const r = await fetch(`${GATEWAY_URL}/api/v1/schemas`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ namespace, name, fields }),
    });
    const d = await r.json();
    if (r.ok) res.redirect(`/?msg=ok&detail=Schema+registrado+(${namespace}.${name})`);
    else       res.redirect(`/?msg=err&detail=${encodeURIComponent(d.message ?? 'Error')}`);
  } catch { res.redirect('/?msg=err&detail=No+se+pudo+conectar+al+gateway'); }
});

app.post('/events', async (req, res) => {
  const { topic, data: dataText } = req.body;
  let data;
  try { data = JSON.parse(dataText); }
  catch { return res.redirect('/?msg=err&detail=JSON+inválido'); }

  try {
    const r = await fetch(`${GATEWAY_URL}/api/v1/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ eventType: topic, data }),
    });
    const d = await r.json();
    if (r.ok) res.redirect(`/?msg=ok&detail=Evento+publicado+(${d.eventId})`);
    else       res.redirect(`/?msg=err&detail=${encodeURIComponent(d.message ?? 'Error')}`);
  } catch { res.redirect('/?msg=err&detail=No+se+pudo+conectar+al+gateway'); }
});

app.listen(PORT, () => console.log(`ejemplo-productor en http://localhost:${PORT}`));
