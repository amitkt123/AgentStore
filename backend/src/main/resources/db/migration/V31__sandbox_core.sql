CREATE TABLE sandbox_profile (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agent(id),
    name VARCHAR(100) NOT NULL,
    inline BOOLEAN NOT NULL DEFAULT FALSE,    -- a one-off profile from POST /sandbox/runs; hidden from listings
    input_json JSONB NOT NULL DEFAULT '{}',
    gate_policy JSONB NOT NULL DEFAULT '{"default":"MANUAL","actions":{}}',
    llm_mode VARCHAR(10),                     -- NULL = real; MOCK | REPLAY
    replay_run_id UUID REFERENCES run(id),
    llm_mock_json JSONB,
    spend_cap NUMERIC(12,2),                  -- NULL = manifest costEnvelope.maxPerRun
    fake_secrets_encrypted BYTEA,
    fake_secrets_nonce BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (agent_id, name)
);

ALTER TABLE run ALTER COLUMN connection_id DROP NOT NULL;
ALTER TABLE run ADD COLUMN initiated_by_org_id UUID REFERENCES org(id);
-- SET NULL: a profile that runs used stays deletable; each run keeps its own copy of every setting.
ALTER TABLE run ADD COLUMN sandbox_profile_id UUID REFERENCES sandbox_profile(id) ON DELETE SET NULL;
ALTER TABLE run ADD COLUMN simulated_charged NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE run ADD COLUMN requested_charge   NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE run ADD CONSTRAINT run_mode_owner_chk CHECK (
    (mode = 'PRODUCTION' AND connection_id IS NOT NULL)
 OR (mode = 'SANDBOX'    AND connection_id IS NULL AND initiated_by_org_id IS NOT NULL)
 OR (mode NOT IN ('PRODUCTION','SANDBOX')));
CREATE INDEX run_sandbox_org_idx ON run (initiated_by_org_id, started_at) WHERE mode = 'SANDBOX';
