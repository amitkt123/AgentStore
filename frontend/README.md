# Karya frontend

Next.js 14 App Router app built to `fe-mockups/latest/Karya — Agentic UI.html`.
Design: `docs/superpowers/specs/2026-09-29-fe-rebuild-p0-p1-design.md`.

## Environment
- `BACKEND_URL` (required): base URL of the backend, e.g. `http://localhost:8080`.

## Develop
    cd frontend && npm install && BACKEND_URL=http://localhost:8080 npm run dev

## Test
    npm test                # unit and component tests
    npm run seed:e2e && npm run test:e2e   # needs backend + Postgres running

## E2E seed
`npm run seed:e2e` needs the backend and Postgres running (default `postgres://agentstore:agentstore@localhost:5433/agentstore`, override with `DATABASE_URL`).
It creates `e2e-buyer@example.com` (buyer admin) and `e2e-approver@example.com` (approver), password `e2e-password`, a LIVE agent
"E2E Bank Reconciliation", a funded wallet and a run `R-E2E1` waiting on an approval. The approval spec consumes that gate, so
re-run the seed before each e2e run: it puts `R-E2E1` back to waiting. Set `E2E_PORT` to run the dev server on another port.
