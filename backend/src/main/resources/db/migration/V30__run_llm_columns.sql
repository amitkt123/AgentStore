ALTER TABLE org ADD COLUMN developer_verified_at TIMESTAMPTZ;   -- nothing in WS3 sets it; WS7 will

ALTER TABLE run ADD COLUMN llm_tokens_used INT NOT NULL DEFAULT 0 CHECK (llm_tokens_used >= 0);
ALTER TABLE run ADD COLUMN llm_tokens_reserved INT NOT NULL DEFAULT 0 CHECK (llm_tokens_reserved >= 0);
ALTER TABLE run ADD COLUMN llm_mode VARCHAR(10);                -- MOCK | REPLAY; null means real
ALTER TABLE run ADD COLUMN replay_run_id UUID;
ALTER TABLE run ADD COLUMN llm_mock_json JSONB;
ALTER TABLE run ADD CONSTRAINT run_llm_mode_not_production CHECK (llm_mode IS NULL OR mode <> 'PRODUCTION');
