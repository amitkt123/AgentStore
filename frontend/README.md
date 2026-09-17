# Agent Store frontend

Next.js 14 App Router app: buyer console under `(buyer)/`, developer console under
`(developer)/dashboard/`, admin console under `(admin)/`.

## Environment variables

- `BACKEND_URL` — **required**. Base URL of the backend API (e.g. `http://localhost:8080`).
  All server-side requests (`src/lib/backend.ts`) read this at request time; there is no
  default, so the app will fail to reach the backend if it's unset.

## Local development

1. Start Postgres locally with an `agentstore` role/database (already assumed to exist on
   this machine at `localhost:5432`).
2. Start the backend:

   ```
   cd backend && mvn spring-boot:run
   ```

   No `e2b` profile is needed for local frontend development. Backend tests require
   `TESTCONTAINERS_RYUK_DISABLED=true` on this machine's Colima setup, e.g.:

   ```
   TESTCONTAINERS_RYUK_DISABLED=true mvn test
   ```

3. Start the frontend:

   ```
   cd frontend && npm run dev
   ```

## Running the E2E test

The E2E suite expects a freshly-seeded backend.

```
npm run seed:e2e   # one-shot: creates dev/buyer/approver accounts, an agent, a wallet
npm run test:e2e   # runs the Playwright suite against the seeded backend
```

**The seed script is NOT idempotent.** It's designed to run exactly once against a fresh
database. Re-running it against a database that already has the seeded accounts will fail
with duplicate-email signup errors — reset the database (or point `DATABASE_URL` /
`BACKEND_URL` at a fresh instance) before re-seeding.
