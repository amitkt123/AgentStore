CREATE TABLE ledger_entry (
    id UUID PRIMARY KEY,
    usage_event_id UUID NOT NULL UNIQUE,
    run_id UUID NOT NULL,
    org_id UUID NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    type VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
