ALTER TABLE dataset ADD COLUMN pii_scan JSONB;

ALTER TABLE sandbox_profile ADD COLUMN description VARCHAR(255);
ALTER TABLE sandbox_profile ADD COLUMN trial_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE sandbox_profile ADD COLUMN no_real_data_attested_by UUID REFERENCES app_user(id);
ALTER TABLE sandbox_profile ADD COLUMN no_real_data_attested_at TIMESTAMPTZ;
ALTER TABLE sandbox_profile ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'PORTAL';
ALTER TABLE sandbox_profile ADD COLUMN repo_source_hash VARCHAR(64);
