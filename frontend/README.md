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
