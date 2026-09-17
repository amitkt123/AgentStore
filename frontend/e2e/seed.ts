import { Client } from "pg";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";
const DATABASE_URL = process.env.DATABASE_URL ?? "postgres://postgres:postgres@localhost:5432/agentstore";

async function signup(orgName: string, orgType: "BUYER" | "DEVELOPER", email: string, password: string, role: string) {
  const res = await fetch(`${BACKEND_URL}/api/v1/auth/signup`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ orgName, orgType, email, password, role }),
  });
  if (!res.ok) throw new Error(`Signup failed for ${email}: ${res.status}`);
  return (await res.json()) as { token: string };
}

async function main() {
  const dev = await signup("E2E Dev Co", "DEVELOPER", "e2e-dev@example.com", "e2e-password", "DEVELOPER");
  await signup("E2E Buyer Co", "BUYER", "e2e-buyer@example.com", "e2e-password", "BUYER_ADMIN");

  const agent = (await fetch(`${BACKEND_URL}/api/v1/agents`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${dev.token}` },
    body: JSON.stringify({ name: "E2E Bank Reconciliation", category: "reconciliation", description: "seeded for e2e" }),
  }).then((r) => r.json())) as { id: string };

  const manifest = {
    name: "E2E Bank Reconciliation",
    version: "1.0.0",
    scopes: [{ type: "READ", resource: "bank_statement", description: "Read uploaded bank statement rows" }],
    network: { allowlist: [] },
    costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "reconciled_transaction" },
    modelRequirements: { provider: "anthropic", model: "claude-sonnet-5" },
    approvalGates: [],
    dataClasses: ["BANK_STATEMENT"],
  };

  const version = (await fetch(`${BACKEND_URL}/api/v1/agents/${agent.id}/versions`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${dev.token}` },
    body: JSON.stringify({ versionNumber: "1.0.0", manifestJson: JSON.stringify(manifest), imageRef: "e2e-template", changelog: "seed" }),
  }).then((r) => r.json())) as { id: string };

  await fetch(`${BACKEND_URL}/api/v1/agents/${agent.id}/versions/${version.id}/submit`, {
    method: "POST",
    headers: { Authorization: `Bearer ${dev.token}` },
  });

  const pg = new Client({ connectionString: DATABASE_URL });
  await pg.connect();
  const buyerOrgRow = await pg.query("select id from org where name = $1", ["E2E Buyer Co"]);
  await pg.query(
    `insert into wallet (id, org_id, balance, cap, updated_at) values (gen_random_uuid(), $1, 100000.00, null, now())
     on conflict (org_id) do update set balance = excluded.balance`,
    [buyerOrgRow.rows[0].id],
  );
  await pg.end();

  console.log("Seed complete. Buyer login: e2e-buyer@example.com / e2e-password");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
