ALTER TABLE run ADD COLUMN mode VARCHAR(20) NOT NULL DEFAULT 'PRODUCTION';
ALTER TABLE run ADD COLUMN violation_count INT NOT NULL DEFAULT 0;
ALTER TABLE run ADD COLUMN input_json JSONB NOT NULL DEFAULT '{}';

-- On /runtime/v1 the token, not the run id in the path, identifies the run. Deliberately not UNIQUE: production
-- tokens are random 256-bit values, but existing tests give several runs the same fixed token string.
CREATE INDEX run_callback_token_hash_idx ON run (callback_token_hash);

ALTER TABLE run_step ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'AGENT';
ALTER TABLE run_step ALTER COLUMN kind TYPE VARCHAR(30);
UPDATE run_step SET source = 'PLATFORM' WHERE kind IN ('TIMEOUT', 'COST_CAP_REACHED');
