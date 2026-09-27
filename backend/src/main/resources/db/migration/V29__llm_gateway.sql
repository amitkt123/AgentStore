CREATE TABLE llm_model (
    id VARCHAR(100) PRIMARY KEY,                  -- the provider's model id, e.g. claude-sonnet-5
    provider VARCHAR(20) NOT NULL,                -- ANTHROPIC | OPENAI
    display_name VARCHAR(200) NOT NULL,
    provider_retains_data BOOLEAN NOT NULL DEFAULT TRUE,
    input_price_per_mtok NUMERIC(12,4) NOT NULL DEFAULT 0,
    output_price_per_mtok NUMERIC(12,4) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    enabled BOOLEAN NOT NULL DEFAULT FALSE
);

-- The two models the parent spec names, disabled with zero prices: real prices are an operator decision.
INSERT INTO llm_model (id, provider, display_name) VALUES
    ('claude-sonnet-5', 'ANTHROPIC', 'Claude Sonnet 5'),
    ('claude-haiku-4-5-20251001', 'ANTHROPIC', 'Claude Haiku 4.5');

CREATE TABLE llm_call (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES run(id),
    model_id VARCHAR(100) NOT NULL REFERENCES llm_model(id),
    source VARCHAR(12) NOT NULL,                  -- PLATFORM | BYOK_PROXY | MOCK | REPLAY
    status VARCHAR(16) NOT NULL,                  -- OK | ERROR | BUDGET_REJECTED | REPLAY_MISS
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    cost NUMERIC(12,4) NOT NULL DEFAULT 0,
    latency_ms INT NOT NULL DEFAULT 0,
    request_hash VARCHAR(64) NOT NULL,            -- SHA-256 hex of the canonical request
    replay_ordinal INT,
    request_key VARCHAR(500),                     -- object-store keys; null unless recorded
    response_key VARCHAR(500),
    recording_expires_at TIMESTAMPTZ,
    recording_purged_at TIMESTAMPTZ,
    error_code VARCHAR(50),                       -- our code, never a provider body
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX llm_call_run_created_idx ON llm_call (run_id, created_at);
CREATE INDEX llm_call_run_hash_idx ON llm_call (run_id, request_hash);
CREATE UNIQUE INDEX llm_call_replay_ordinal_idx ON llm_call (run_id, request_hash, replay_ordinal)
    WHERE replay_ordinal IS NOT NULL;
CREATE INDEX llm_call_recording_expiry_idx ON llm_call (recording_expires_at)
    WHERE recording_purged_at IS NULL AND recording_expires_at IS NOT NULL;

CREATE TABLE byok_credential (
    id UUID PRIMARY KEY,
    developer_org_id UUID NOT NULL REFERENCES org(id),
    provider VARCHAR(20) NOT NULL,
    encrypted_key BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    last4 VARCHAR(8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (developer_org_id, provider)
);
