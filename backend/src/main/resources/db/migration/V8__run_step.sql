CREATE TABLE run_step (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES run(id),
    kind VARCHAR(20) NOT NULL,
    detail JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
