import { Client } from "pg";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";
const DATABASE_URL = process.env.DATABASE_URL ?? "postgres://agentstore:agentstore@localhost:5433/agentstore";
const PASSWORD = "e2e-password";

async function signup(fullName: string, orgName: string, side: "BUYER" | "DEVELOPER", email: string) {
  const res = await fetch(`${BACKEND_URL}/api/v1/auth/signup`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fullName, orgName, side, email, password: PASSWORD }),
  });
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    if (res.status === 409) {
      // Re-running after a partial seed: the user exists, so sign in instead.
      const login = await fetch(`${BACKEND_URL}/api/v1/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password: PASSWORD, remember: false }),
      });
      if (login.ok) return (await login.json()) as { token: string };
    }
    throw new Error(`Signup failed for ${email}: ${res.status} ${body}`);
  }
  return (await res.json()) as { token: string };
}

// The approval spec consumes the seeded gate, so a re-run puts the run back to waiting instead of seeding twice.
async function resetIfSeeded(): Promise<boolean> {
  const pg = new Client({ connectionString: DATABASE_URL });
  await pg.connect();
  try {
    const run = await pg.query("select id from run where display_id='R-E2E1'");
    if (run.rowCount === 0) return false;
    const id = run.rows[0].id;
    await pg.query(
      "update run set status='AWAITING_APPROVAL', terminal_reason=null, completed_at=null, awaiting_since=now(), deadline_at=now() + interval '1 hour' where id=$1",
      [id],
    );
    await pg.query(
      "update gate_request set status='PENDING', decided_by_user_id=null, decision_note=null, decided_at=null, decided_via=null, expires_at=now() + interval '1 day' where run_id=$1",
      [id],
    );
    return true;
  } finally {
    await pg.end();
  }
}

async function main() {
  if (await resetIfSeeded()) {
    console.log("Already seeded: put run R-E2E1 back to waiting for approval.");
    return;
  }
  const dev = await signup("E2E Developer", "E2E Dev Co", "DEVELOPER", "e2e-dev@example.com");
  await signup("E2E Buyer Admin", "E2E Buyer Co", "BUYER", "e2e-buyer@example.com");
  // Signup always creates a new org, so the approver signs up into a throwaway org and is then moved into the buyer org.
  await signup("E2E Approver", "E2E Buyer Approver Org (temp)", "BUYER", "e2e-approver@example.com");

  const auth = (t: string) => ({ "Content-Type": "application/json", Authorization: `Bearer ${t}` });
  const agent = (await fetch(`${BACKEND_URL}/api/v1/agents`, {
    method: "POST",
    headers: auth(dev.token),
    body: JSON.stringify({ name: "E2E Bank Reconciliation", category: "reconciliation", description: "seeded for e2e" }),
  }).then((r) => r.json())) as { id: string };

  const manifest = {
    name: "E2E Bank Reconciliation",
    version: "1.0.0",
    scopes: [
      { type: "READ", resource: "bank_statement", description: "Bank statement lines" },
      { type: "WRITE", resource: "ledger", description: "Post entries", requiresApproval: true },
    ],
    resources: [{ name: "bank_statement", format: "csv", schema: { required: ["date", "narration", "amount"], properties: {} } },
      { name: "ledger", format: "json" }],
    network: { allowlist: [] },
    costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "reconciled_transaction" },
    approvalGates: [{ trigger: "post_entries", description: "Before anything is posted", timeoutMinutes: 30 }],
    dataClasses: ["FINANCIAL"],
  };

  const vres = await fetch(`${BACKEND_URL}/api/v1/agents/${agent.id}/versions`, {
    method: "POST",
    headers: auth(dev.token),
    body: JSON.stringify({ versionNumber: "1.0.0", manifestJson: JSON.stringify(manifest), changelog: "seed" }),
  });
  if (!vres.ok) throw new Error(`Version create failed: ${vres.status} ${await vres.text()}`);
  const version = (await vres.json()) as { id: string };

  const pg = new Client({ connectionString: DATABASE_URL });
  await pg.connect();
  // No bundle or build in the seed, so the version is made LIVE directly.
  await pg.query("update agent_version set status='LIVE', approved_at=now(), image_ref='e2e-template' where id=$1", [version.id]);

  const buyerOrgId = (await pg.query("select id from org where name=$1", ["E2E Buyer Co"])).rows[0].id;
  await pg.query(
    `insert into wallet (id, org_id, balance, cap, updated_at) values (gen_random_uuid(), $1, 100000.00, null, now())
     on conflict (org_id) do update set balance = excluded.balance`,
    [buyerOrgId],
  );

  const approverOrgId = (await pg.query("select id from org where name=$1", ["E2E Buyer Approver Org (temp)"])).rows[0].id;
  await pg.query(
    `update org_membership set org_id=$1, role='BUYER_APPROVER', title='Accounts manager'
       where org_id=$2 and user_id=(select id from app_user where email=$3)`,
    [buyerOrgId, approverOrgId, "e2e-approver@example.com"],
  );
  await pg.query("update app_user set last_org_id=$1 where email=$2", [buyerOrgId, "e2e-approver@example.com"]);
  await pg.query("update app_user set signup_org_id=$1 where email=$2", [buyerOrgId, "e2e-approver@example.com"]);
  await pg.query("delete from org where id=$1", [approverOrgId]);

  // A run already waiting on a gate: a real sandbox run is out of reach for e2e.
  const connectionId = (await pg.query(
    `insert into connection (id, buyer_org_id, agent_id, granted_scopes, spend_cap, status, agent_version_id)
     values (gen_random_uuid(), $1, $2, '["bank_statement","ledger"]', 500, 'ACTIVE', $3) returning id`,
    [buyerOrgId, agent.id, version.id],
  )).rows[0].id;
  const runId = (await pg.query(
    `insert into run (id, connection_id, agent_version_id, status, cost_cap, callback_token_hash, started_at, deadline_at,
                      display_id, reached_block, awaiting_since)
     values (gen_random_uuid(), $1, $2, 'AWAITING_APPROVAL', 500, md5(random()::text), now() - interval '5 minutes',
             now() + interval '1 hour', 'R-E2E1', 'gate:post_entries', now())
     returning id`,
    [connectionId, version.id],
  )).rows[0].id;
  const gateId = (await pg.query(
    `insert into gate_request (id, run_id, action, summary, payload, status, expires_at, preview)
     values (gen_random_uuid(), $1, 'post_entries', 'Post 142 journal entries for August?', '{}', 'PENDING',
             now() + interval '1 day', $2::jsonb)
     returning id`,
    [runId, JSON.stringify({ columns: ["party", "amount"], rows: [{ party: "NEFT ACME 0814", amount: 48200 }], totalRows: 142 })],
  )).rows[0].id;
  await pg.query(
    `insert into run_step (id, run_id, kind, detail, block, source) values
       (gen_random_uuid(), $1, 'READ', '{"count":412}', 'read:bank_statement', 'PLATFORM'),
       (gen_random_uuid(), $1, 'GATE_REQUESTED', '{}', 'gate:post_entries', 'PLATFORM')`,
    [runId],
  );
  await pg.end();

  console.log("Buyer admin login:    e2e-buyer@example.com / e2e-password");
  console.log("Buyer approver login: e2e-approver@example.com / e2e-password");
  console.log(JSON.stringify({ agentId: agent.id, versionId: version.id, runId, gateId }));
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
