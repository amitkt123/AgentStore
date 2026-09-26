CREATE TABLE gate_request (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES run(id),
    action VARCHAR(100) NOT NULL,
    summary TEXT NOT NULL,
    payload JSONB NOT NULL,
    affects_resources JSONB NOT NULL DEFAULT '[]',
    status VARCHAR(20) NOT NULL,
    decided_by_user_id UUID,          -- no FK: tests decide with synthetic user ids
    decision_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX gate_request_run_status_idx ON gate_request (run_id, status);
