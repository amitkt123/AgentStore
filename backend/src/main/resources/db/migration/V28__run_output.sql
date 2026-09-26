CREATE TABLE run_output (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES run(id),
    resource_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    record_count INT NOT NULL,
    size_bytes BIGINT NOT NULL,
    storage_key VARCHAR(500),            -- JSONL chunk; null once its blob was purged
    gate_request_id UUID REFERENCES gate_request(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX run_output_run_status_idx ON run_output (run_id, status);
