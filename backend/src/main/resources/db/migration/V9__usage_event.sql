CREATE TABLE usage_event (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    org_id UUID NOT NULL,
    quantity NUMERIC(14,4) NOT NULL,
    unit_cost NUMERIC(14,4) NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
