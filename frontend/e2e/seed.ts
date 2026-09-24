import { Client } from "pg";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";
const DATABASE_URL = process.env.DATABASE_URL ?? "postgres://postgres:postgres@localhost:5432/agentstore";

async function signup(fullName: string, orgName: string, side: "BUYER" | "DEVELOPER", email: string, password: string) {
  const res = await fetch(`${BACKEND_URL}/api/v1/auth/signup`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fullName, orgName, side, email, password }),
  });
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    // A duplicate email returns 409 (see AuthController#signup). That's by far the most common
    // cause of a seed failure (re-running the script against a database it already seeded), so we
    // surface that as the likely explanation instead of a raw stack trace, while still showing the
    // underlying status for anything genuinely new.
    if (res.status === 409) {
      throw new Error(
        `Signup failed for ${email} (status ${res.status}). This is most likely because the email is already ` +
          `registered — the seed script is meant to run once against a fresh database; see README.md before ` +
          `re-running it. Raw response: ${body}`,
      );
    }
    throw new Error(`Signup failed for ${email}: ${res.status} ${body}`);
  }
  return (await res.json()) as { token: string };
}

async function main() {
  const dev = await signup("E2E Developer", "E2E Dev Co", "DEVELOPER", "e2e-dev@example.com", "e2e-password");
  await signup("E2E Buyer Admin", "E2E Buyer Co", "BUYER", "e2e-buyer@example.com", "e2e-password");
  // Signup always creates a brand-new org (see AuthController#signup) — there is no "join an existing org"
  // endpoint. To get a BUYER_APPROVER into the SAME org as the BUYER_ADMIN above, sign up into a throwaway
  // org (so the backend does the bcrypt hashing + validation for us) and then move the user's membership
  // into the real buyer org via SQL, cleaning up the throwaway org afterwards.
  await signup("E2E Approver", "E2E Buyer Approver Org (temp)", "BUYER", "e2e-approver@example.com", "e2e-password");

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
  const buyerOrgId = buyerOrgRow.rows[0].id;
  await pg.query(
    `insert into wallet (id, org_id, balance, cap, updated_at) values (gen_random_uuid(), $1, 100000.00, null, now())
     on conflict (org_id) do update set balance = excluded.balance`,
    [buyerOrgId],
  );

  // Memberships, not app_user.org_id, decide which workspace a person belongs to (WS13).
  const approverOrgRow = await pg.query("select id from org where name = $1", ["E2E Buyer Approver Org (temp)"]);
  const approverOrgId = approverOrgRow.rows[0].id;
  await pg.query(
    `update org_membership set org_id = $1, role = 'BUYER_APPROVER', title = 'Accounts manager'
       where org_id = $2 and user_id = (select id from app_user where email = $3)`,
    [buyerOrgId, approverOrgId, "e2e-approver@example.com"],
  );
  await pg.query(`update app_user set last_org_id = $1 where email = $2`, [buyerOrgId, "e2e-approver@example.com"]);
  await pg.query(`delete from org where id = $1`, [approverOrgId]);

  await pg.end();

  console.log("Seed complete.");
  console.log("Buyer admin login:    e2e-buyer@example.com / e2e-password");
  console.log("Buyer approver login: e2e-approver@example.com / e2e-password");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
