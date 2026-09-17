import { Client } from "pg";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";
const DATABASE_URL = process.env.DATABASE_URL ?? "postgres://postgres:postgres@localhost:5432/agentstore";

async function signup(orgName: string, orgType: "BUYER" | "DEVELOPER", email: string, password: string, role: string) {
  const res = await fetch(`${BACKEND_URL}/api/v1/auth/signup`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ orgName, orgType, email, password, role }),
  });
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    // The backend has no global exception handler, so a duplicate email surfaces as a generic
    // 500 (a DataIntegrityViolationException on the app_user.email unique constraint) rather than
    // a clean 4xx — the JSON body carries no distinguishing detail beyond status/path. That 500 on
    // this endpoint is by far the most common cause of a seed failure (re-running the script
    // against a database it already seeded), so we surface that as the likely explanation instead
    // of a raw stack trace, while still showing the underlying status for anything genuinely new.
    if (res.status === 500 || (res.status >= 400 && res.status < 500 && /email|unique|duplicate|already/i.test(body))) {
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
  const dev = await signup("E2E Dev Co", "DEVELOPER", "e2e-dev@example.com", "e2e-password", "DEVELOPER");
  await signup("E2E Buyer Co", "BUYER", "e2e-buyer@example.com", "e2e-password", "BUYER_ADMIN");
  // Signup always creates a brand-new org (see AuthController#signup) — there is no "join an existing org"
  // endpoint. To get a BUYER_APPROVER into the SAME org as the BUYER_ADMIN above, sign up into a throwaway
  // org (so the backend does the bcrypt hashing + validation for us) and then reassign the user's org_id
  // via SQL, cleaning up the throwaway org afterwards.
  await signup("E2E Buyer Approver Org (temp)", "BUYER", "e2e-approver@example.com", "e2e-password", "BUYER_APPROVER");

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

  // Move the approver user into the real buyer org, then drop the throwaway org signup created for it.
  const approverOrgRow = await pg.query("select id from org where name = $1", ["E2E Buyer Approver Org (temp)"]);
  const approverOrgId = approverOrgRow.rows[0].id;
  await pg.query(`update app_user set org_id = $1 where email = $2`, [buyerOrgId, "e2e-approver@example.com"]);
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
