ALTER TABLE org ADD COLUMN storage_limit_bytes BIGINT NOT NULL DEFAULT 5368709120;

CREATE TABLE dataset (
    id UUID PRIMARY KEY,
    owner_org_id UUID NOT NULL REFERENCES org(id),
    purpose VARCHAR(20) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    source_label VARCHAR(200),
    uploaded_by_user_id UUID,            -- no FK: tests upload with synthetic user ids
    format VARCHAR(10) NOT NULL,
    storage_key VARCHAR(500),            -- null once purged
    records_key VARCHAR(500),            -- normalized JSONL; null for PDF and once purged
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    records_size_bytes BIGINT NOT NULL DEFAULT 0,
    row_count BIGINT,                    -- null for PDF
    columns JSONB NOT NULL DEFAULT '[]',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    purge_after TIMESTAMPTZ,
    purged_at TIMESTAMPTZ
);
CREATE INDEX dataset_org_idx ON dataset (owner_org_id) WHERE purged_at IS NULL;
CREATE INDEX dataset_purge_idx ON dataset (purge_after) WHERE purged_at IS NULL AND purge_after IS NOT NULL;

CREATE TABLE dataset_binding (
    id UUID PRIMARY KEY,
    owner_type VARCHAR(20) NOT NULL,
    owner_id UUID NOT NULL,
    resource_name VARCHAR(100) NOT NULL,
    dataset_id UUID NOT NULL REFERENCES dataset(id),
    check_result JSONB NOT NULL DEFAULT '{}',
    bound_by_user_id UUID,
    bound_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT dataset_binding_owner_resource_uq UNIQUE (owner_type, owner_id, resource_name)
);
CREATE INDEX dataset_binding_dataset_idx ON dataset_binding (dataset_id);

-- What each run reads. Written at run creation; a purged dataset is tombstoned, never deleted, so this FK never dangles.
CREATE TABLE run_dataset_pin (
    run_id UUID NOT NULL REFERENCES run(id),
    resource_name VARCHAR(100) NOT NULL,
    dataset_id UUID NOT NULL REFERENCES dataset(id),
    sha256 VARCHAR(64) NOT NULL,
    PRIMARY KEY (run_id, resource_name)
);
CREATE INDEX run_dataset_pin_dataset_idx ON run_dataset_pin (dataset_id);
