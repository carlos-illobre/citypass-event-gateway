# frontend

Aplicaciones de interfaz del **Grupo 1 (EDA)** para el bus de eventos de CityPass+.

```
frontend/
├── dashboard/       Consola de observabilidad del bus. React + Vite. Sprint 1
└── backend-front/   Consumidor de Kafka con push al navegador. Vacío. Sprint 2
```

La UI de administración del gateway —publicar eventos, crear tipos— es
[`../event-gateway-ui`](../event-gateway-ui) y no se toca desde acá: es otra aplicación, con otro
propósito. Esta consola es de **sólo lectura**.

## Arrancar

```bash
# 1. El stack del bus, desde la raíz del repositorio
docker compose up -d

# 2. El tablero
cd frontend/dashboard
cp .env.example .env
npm install
npm run dev            # http://localhost:5175
```

Credenciales de ejemplo: `grupo8` / `grupo8`. El grupo 1 todavía no tiene cliente propio en el
simulador de identidad —la lista va de `grupo2` a `grupo8`—, así que el tablero entra con el
namespace de analítica, que es el que mejor le calza.

Para que las pantallas no estén vacías:

```bash
node scripts/seed-demo.mjs
```

## Verificar

```bash
./test-frontend.sh     # tipos + lint + tests con cobertura
```

## Documentación

| Documento | Qué contiene |
|---|---|
| [`dashboard/README.md`](dashboard/README.md) | Detalle técnico del tablero |
| [`backend-front/README.md`](backend-front/README.md) | Por qué está vacío y qué va a construir el Sprint 2 |

Las notas de planificación (backlog, tarjetas de sprint, seguimiento de tickets de Jira) son de
uso personal y no viajan en este repositorio — ver `frontend/docs/` en el propio checkout si
hace falta retomarlas.

## Puertos

| Puerto | Qué |
|---|---|
| 5173 | `event-gateway-ui` servida por Docker |
| 5174 | `event-gateway-ui` en modo desarrollo |
| **5175** | **`dashboard`, este proyecto** |
| 8085 | Reservado para `backend-front` |
